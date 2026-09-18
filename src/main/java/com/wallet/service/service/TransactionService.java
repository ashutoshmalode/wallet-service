package com.wallet.service.service;

import com.wallet.service.config.IdempotencyCache;
import com.wallet.service.dto.TransactionRequest;
import com.wallet.service.dto.TransactionResponse;
import com.wallet.service.entity.Transaction;
import com.wallet.service.entity.Transaction.TransactionStatus;
import com.wallet.service.entity.Transaction.TransactionType;
import com.wallet.service.entity.Wallet;
import com.wallet.service.exception.DuplicateTransactionException;
import com.wallet.service.exception.InsufficientFundsException;
import com.wallet.service.exception.WalletNotFoundException;
import com.wallet.service.repository.TransactionRepository;
import com.wallet.service.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final IdempotencyCache idempotencyCache;

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TransactionResponse process(TransactionRequest request) {
        UUID txId = request.getTransactionId();
        log.debug("Processing transaction: {}", txId);

        //  Level-1 idempotency: check DB for an already-committed record
        var existing = transactionRepository.findByTransactionId(txId);
        if (existing.isPresent()) {
            log.info("Duplicate transactionId detected in DB: {}", txId);
            throw new DuplicateTransactionException(toResponse(existing.get()));
        }

        // In-flight guard: reject a second concurrent request for the same txId
        boolean acquired = idempotencyCache.tryAcquire(txId);
        if (!acquired) {
            log.info("Concurrent duplicate transactionId detected in-memory: {}", txId);
            // Another thread is mid-flight — check DB one more time (it may have committed)
            var committed = transactionRepository.findByTransactionId(txId);
            if (committed.isPresent()) {
                throw new DuplicateTransactionException(toResponse(committed.get()));
            }
            // Still not committed; build a synthetic 409 response
            TransactionResponse synthetic = TransactionResponse.builder()
                    .transactionId(txId)
                    .userId(request.getUserId())
                    .amount(request.getAmount())
                    .type(request.getType())
                    .status(TransactionStatus.DUPLICATE)
                    .message("Concurrent duplicate request rejected")
                    .timestamp(LocalDateTime.now())
                    .build();
            throw new DuplicateTransactionException(synthetic);
        }

        try {
            return executeTransaction(request);
        } finally {
            idempotencyCache.release(txId);
        }
    }

    private TransactionResponse executeTransaction(TransactionRequest request) {
        // Acquire PESSIMISTIC_WRITE lock on wallet row 
        Wallet wallet = walletRepository.findByUserIdWithLock(request.getUserId())
                .orElseThrow(() -> new WalletNotFoundException(request.getUserId()));

        log.debug("Acquired lock on wallet {} — current balance: {}", request.getUserId(), wallet.getBalance());

        Transaction tx;

        if (request.getType() == TransactionType.DEBIT) {
            int cmp = wallet.getBalance().compareTo(request.getAmount());
            if (cmp < 0) {
                // Save a FAILED record so that retries are still idempotent
                tx = Transaction.builder()
                        .transactionId(request.getTransactionId())
                        .userId(request.getUserId())
                        .amount(request.getAmount())
                        .type(request.getType())
                        .status(TransactionStatus.FAILED)
                        .balanceAfter(wallet.getBalance())
                        .failureReason("Insufficient funds")
                        .createdAt(LocalDateTime.now())
                        .build();
                transactionRepository.save(tx);
                throw new InsufficientFundsException(
                        "Insufficient funds. Balance: " + wallet.getBalance() + ", requested: " + request.getAmount());
            }
            wallet.setBalance(wallet.getBalance().subtract(request.getAmount()));
        } else {
            wallet.setBalance(wallet.getBalance().add(request.getAmount()));
        }

        walletRepository.save(wallet);

        tx = Transaction.builder()
                .transactionId(request.getTransactionId())
                .userId(request.getUserId())
                .amount(request.getAmount())
                .type(request.getType())
                .status(TransactionStatus.SUCCESS)
                .balanceAfter(wallet.getBalance())
                .createdAt(LocalDateTime.now())
                .build();
        transactionRepository.save(tx);

        log.info("Transaction {} completed. New balance: {}", request.getTransactionId(), wallet.getBalance());

        return toResponse(tx);
    }

    private TransactionResponse toResponse(Transaction tx) {
        return TransactionResponse.builder()
                .transactionId(tx.getTransactionId())
                .userId(tx.getUserId())
                .amount(tx.getAmount())
                .type(tx.getType())
                .status(tx.getStatus())
                .balanceAfter(tx.getBalanceAfter())
                .message(tx.getStatus() == TransactionStatus.SUCCESS
                        ? "Transaction processed successfully"
                        : tx.getFailureReason())
                .timestamp(tx.getCreatedAt())
                .build();
    }

    // Wallet management helpers (used by tests & seed data)

    @Transactional
    public Wallet createWallet(UUID userId, java.math.BigDecimal initialBalance) {
        Wallet wallet = Wallet.builder()
                .userId(userId)
                .balance(initialBalance)
                .build();
        return walletRepository.save(wallet);
    }

    @Transactional(readOnly = true)
    public Wallet getWallet(UUID userId) {
        return walletRepository.findById(userId)
                .orElseThrow(() -> new WalletNotFoundException(userId));
    }
}
