package com.wallet.service;

import com.wallet.service.dto.TransactionRequest;
import com.wallet.service.dto.TransactionResponse;
import com.wallet.service.entity.Transaction.TransactionStatus;
import com.wallet.service.entity.Transaction.TransactionType;
import com.wallet.service.entity.Wallet;
import com.wallet.service.service.TransactionService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TransactionServiceIntegrationTest {

    @Autowired
    private TransactionService transactionService;

    // Test 1: Happy Path

    @Test
    @Order(1)
    @DisplayName("Processes a single valid debit transaction successfully.")
    void happyPath_singleValidDebitTransaction_succeeds() {
        System.out.println("\n========================================================");
        System.out.println("TEST 1: Happy Path");
        System.out.println("Intent : Process a single valid debit transaction.");
        System.out.println("========================================================");

        // ARRANGE
        UUID userId = UUID.randomUUID();
        BigDecimal initialBalance = new BigDecimal("1000.00");
        transactionService.createWallet(userId, initialBalance);
        System.out.println("  Wallet created — userId=" + userId + ", balance=₹" + initialBalance);

        UUID transactionId = UUID.randomUUID();
        TransactionRequest request = TransactionRequest.builder()
                .transactionId(transactionId)
                .userId(userId)
                .amount(new BigDecimal("250.00"))
                .type(TransactionType.DEBIT)
                .build();

        // ACT
        System.out.println("  Sending debit request — amount=₹250.00 ...");
        TransactionResponse response = transactionService.process(request);

        // ASSERT
        Wallet walletAfter = transactionService.getWallet(userId);

        System.out.println("  Response status : " + response.getStatus());
        System.out.println("  Balance after   : ₹" + walletAfter.getBalance());
        System.out.println("  Expected balance: ₹750.00");

        assertThat(response.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(response.getTransactionId()).isEqualTo(transactionId);
        assertThat(response.getBalanceAfter()).isEqualByComparingTo("750.00");
        assertThat(walletAfter.getBalance()).isEqualByComparingTo("750.00");

        System.out.println("  ✅ PASSED — Balance correctly deducted to ₹750.00");
    }

    // Test 2: Idempotency

    @Test
    @Order(2)
    @DisplayName("Sends 3 identical transactionIDs simultaneously. Ensures the balance is only deducted once.")
    void idempotency_threeConcurrentIdenticalTransactionIds_balanceDeductedOnlyOnce() throws InterruptedException {
        System.out.println("\n========================================================");
        System.out.println("TEST 2: Idempotency");
        System.out.println("Intent : 3 concurrent requests with the same transactionId.");
        System.out.println("         Exactly one must succeed; balance deducted only once.");
        System.out.println("========================================================");

        // ARRANGE
        UUID userId = UUID.randomUUID();
        BigDecimal initialBalance = new BigDecimal("500.00");
        transactionService.createWallet(userId, initialBalance);
        System.out.println("  Wallet created — userId=" + userId + ", balance=₹" + initialBalance);

        UUID sharedTransactionId = UUID.randomUUID();  // Same transactionId for all 3
        System.out.println("  Shared transactionId: " + sharedTransactionId);

        int threadCount = 3;
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        List<Future<String>> futures = new ArrayList<>();

        // ACT — launch 3 threads simultaneously
        for (int i = 0; i < threadCount; i++) {
            final int threadNum = i + 1;
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();  // All threads wait here until fired together

                TransactionRequest req = TransactionRequest.builder()
                        .transactionId(sharedTransactionId)
                        .userId(userId)
                        .amount(new BigDecimal("100.00"))
                        .type(TransactionType.DEBIT)
                        .build();

                try {
                    transactionService.process(req);
                    successCount.incrementAndGet();
                    return "Thread-" + threadNum + ": SUCCESS";
                } catch (com.wallet.service.exception.DuplicateTransactionException e) {
                    conflictCount.incrementAndGet();
                    return "Thread-" + threadNum + ": CONFLICT (409) — " + e.getMessage();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    return "Thread-" + threadNum + ": ERROR — " + e.getMessage();
                }
            }));
        }

        readyLatch.await();   // Wait until all threads are lined up
        startLatch.countDown(); // Fire all at once

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Collect results
        System.out.println("\n  Results:");
        for (Future<String> f : futures) {
            try {
                System.out.println("    " + f.get());
            } catch (ExecutionException e) {
                System.out.println("    EXECUTION ERROR: " + e.getCause().getMessage());
            }
        }

        // ASSERT
        Wallet walletAfter = transactionService.getWallet(userId);
        System.out.println("\n  Successes  : " + successCount.get() + " (expected 1)");
        System.out.println("  Conflicts  : " + conflictCount.get() + " (expected 2)");
        System.out.println("  Errors     : " + errorCount.get() + " (expected 0)");
        System.out.println("  Final balance: ₹" + walletAfter.getBalance() + " (expected ₹400.00)");

        assertThat(successCount.get()).as("Exactly one request must succeed").isEqualTo(1);
        assertThat(conflictCount.get()).as("Remaining requests must return 409 CONFLICT").isEqualTo(2);
        assertThat(errorCount.get()).as("No unexpected errors").isZero();
        assertThat(walletAfter.getBalance())
                .as("Balance must be deducted exactly once (500 - 100 = 400)")
                .isEqualByComparingTo("400.00");

        System.out.println("  ✅ PASSED — Balance deducted exactly once despite 3 concurrent identical requests.");
    }

    // Test 3: Race Condition

    @Test
    @Order(3)
    @DisplayName("Sends 10 concurrent debit requests of ₹100 for a wallet with a ₹500 balance. Ensures the final balance is exactly ₹0 and 5 requests fail with insufficient funds.")
    void raceCondition_tenConcurrentDebits_exactlyFiveSucceedFiveFail() throws InterruptedException {
        System.out.println("\n========================================================");
        System.out.println("TEST 3: Race Condition");
        System.out.println("Intent : 10 concurrent debit requests of ₹100 each.");
        System.out.println("         Wallet has ₹500. Exactly 5 must succeed.");
        System.out.println("         Final balance must be exactly ₹0.");
        System.out.println("         Remaining 5 must fail with Insufficient Funds.");
        System.out.println("========================================================");

        // ARRANGE
        UUID userId = UUID.randomUUID();
        BigDecimal initialBalance = new BigDecimal("500.00");
        transactionService.createWallet(userId, initialBalance);
        System.out.println("  Wallet created — userId=" + userId + ", balance=₹" + initialBalance);

        int threadCount = 10;
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientFundsCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        List<Future<String>> futures = new ArrayList<>();

        // ACT — each thread uses a UNIQUE transactionId but same userId
        for (int i = 0; i < threadCount; i++) {
            final int threadNum = i + 1;
            final UUID uniqueTxId = UUID.randomUUID();  // Different per thread

            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();

                TransactionRequest req = TransactionRequest.builder()
                        .transactionId(uniqueTxId)
                        .userId(userId)
                        .amount(new BigDecimal("100.00"))
                        .type(TransactionType.DEBIT)
                        .build();

                try {
                    TransactionResponse resp = transactionService.process(req);
                    if (resp.getStatus() == TransactionStatus.SUCCESS) {
                        successCount.incrementAndGet();
                        return "Thread-" + threadNum + ": SUCCESS — balance now ₹" + resp.getBalanceAfter();
                    } else {
                        insufficientFundsCount.incrementAndGet();
                        return "Thread-" + threadNum + ": FAILED (Insufficient) — " + resp.getMessage();
                    }
                } catch (com.wallet.service.exception.InsufficientFundsException e) {
                    insufficientFundsCount.incrementAndGet();
                    return "Thread-" + threadNum + ": FAILED (Insufficient) — " + e.getMessage();
                } catch (com.wallet.service.exception.DuplicateTransactionException e) {
                    conflictCount.incrementAndGet();
                    return "Thread-" + threadNum + ": CONFLICT — " + e.getMessage();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    return "Thread-" + threadNum + ": ERROR — " + e.getClass().getSimpleName() + ": " + e.getMessage();
                }
            }));
        }

        readyLatch.await();
        startLatch.countDown();

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        // Collect results
        System.out.println("\n  Results:");
        for (Future<String> f : futures) {
            try {
                System.out.println("    " + f.get());
            } catch (ExecutionException e) {
                System.out.println("    EXECUTION ERROR: " + e.getCause().getClass().getSimpleName()
                        + ": " + e.getCause().getMessage());
            }
        }

        // ASSERT
        Wallet walletAfter = transactionService.getWallet(userId);
        System.out.println("\n  Successes         : " + successCount.get() + " (expected 5)");
        System.out.println("  Insufficient Funds: " + insufficientFundsCount.get() + " (expected 5)");
        System.out.println("  Conflicts (dup)   : " + conflictCount.get() + " (expected 0)");
        System.out.println("  Errors            : " + errorCount.get() + " (expected 0)");
        System.out.println("  Final balance     : ₹" + walletAfter.getBalance() + " (expected ₹0.00)");

        assertThat(successCount.get())
                .as("Exactly 5 debits of ₹100 should succeed (₹500 / ₹100 = 5)")
                .isEqualTo(5);

        assertThat(insufficientFundsCount.get())
                .as("Remaining 5 requests must fail with Insufficient Funds")
                .isEqualTo(5);

        assertThat(walletAfter.getBalance())
                .as("Final balance must be exactly ₹0.00 — not negative")
                .isEqualByComparingTo("0.00");

        assertThat(walletAfter.getBalance())
                .as("Balance must NEVER go negative")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);

        System.out.println("  ✅ PASSED — Exactly 5 succeeded, 5 failed with insufficient funds, balance = ₹0.00");
    }

    // Test 4: Credit transaction

    @Test
    @Order(4)
    @DisplayName("Processes a credit transaction and increases the wallet balance correctly.")
    void creditTransaction_increasesBalance() {
        System.out.println("\n========================================================");
        System.out.println("TEST 4: Credit Transaction");
        System.out.println("========================================================");

        UUID userId = UUID.randomUUID();
        transactionService.createWallet(userId, new BigDecimal("100.00"));

        TransactionRequest request = TransactionRequest.builder()
                .transactionId(UUID.randomUUID())
                .userId(userId)
                .amount(new BigDecimal("200.00"))
                .type(TransactionType.CREDIT)
                .build();

        TransactionResponse response = transactionService.process(request);

        System.out.println("  Response status: " + response.getStatus());
        System.out.println("  Balance after  : ₹" + response.getBalanceAfter());

        assertThat(response.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(response.getBalanceAfter()).isEqualByComparingTo("300.00");

        System.out.println("  ✅ PASSED — Credit applied correctly. Balance = ₹300.00");
    }

    // Test 5: Insufficient Funds

    @Test
    @Order(5)
    @DisplayName("Returns FAILED status when wallet has insufficient funds for a debit.")
    void debit_insufficientFunds_returnsFailed() {
        System.out.println("\n========================================================");
        System.out.println("TEST 5: Insufficient Funds");
        System.out.println("========================================================");

        UUID userId = UUID.randomUUID();
        transactionService.createWallet(userId, new BigDecimal("50.00"));

        TransactionRequest request = TransactionRequest.builder()
                .transactionId(UUID.randomUUID())
                .userId(userId)
                .amount(new BigDecimal("200.00"))
                .type(TransactionType.DEBIT)
                .build();

        System.out.println("  Attempting to debit ₹200 from wallet with ₹50 ...");

        org.junit.jupiter.api.Assertions.assertThrows(
                com.wallet.service.exception.InsufficientFundsException.class,
                () -> transactionService.process(request),
                "Should throw InsufficientFundsException"
        );

        Wallet walletAfter = transactionService.getWallet(userId);
        System.out.println("  Balance unchanged: ₹" + walletAfter.getBalance());

        assertThat(walletAfter.getBalance()).isEqualByComparingTo("50.00");
        System.out.println("  ✅ PASSED — Insufficient funds correctly rejected, balance unchanged.");
    }

    // Test 6: Wallet Not Found

    @Test
    @Order(6)
    @DisplayName("Throws WalletNotFoundException when userId does not exist.")
    void process_nonExistentWallet_throwsNotFoundException() {
        System.out.println("\n========================================================");
        System.out.println("TEST 6: Wallet Not Found");
        System.out.println("========================================================");

        UUID nonExistentUserId = UUID.randomUUID();
        TransactionRequest request = TransactionRequest.builder()
                .transactionId(UUID.randomUUID())
                .userId(nonExistentUserId)
                .amount(new BigDecimal("100.00"))
                .type(TransactionType.DEBIT)
                .build();

        System.out.println("  Sending request for non-existent userId: " + nonExistentUserId);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.wallet.service.exception.WalletNotFoundException.class,
                () -> transactionService.process(request)
        );

        System.out.println("  ✅ PASSED — WalletNotFoundException thrown as expected.");
    }

    // Test 7: Sequential idempotency (same txId, second call)

    @Test
    @Order(7)
    @DisplayName("Returns 409 DuplicateTransactionException when the same transactionId is submitted a second time sequentially.")
    void idempotency_sequentialDuplicateTransactionId_returns409() {
        System.out.println("\n========================================================");
        System.out.println("TEST 7: Sequential Duplicate TransactionId");
        System.out.println("========================================================");

        UUID userId = UUID.randomUUID();
        transactionService.createWallet(userId, new BigDecimal("500.00"));

        UUID txId = UUID.randomUUID();
        TransactionRequest request = TransactionRequest.builder()
                .transactionId(txId)
                .userId(userId)
                .amount(new BigDecimal("100.00"))
                .type(TransactionType.DEBIT)
                .build();

        // First call — should succeed
        System.out.println("  First call (should succeed) ...");
        TransactionResponse first = transactionService.process(request);
        assertThat(first.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        System.out.println("  First call: " + first.getStatus());

        // Second call — same transactionId, should throw DuplicateTransactionException
        System.out.println("  Second call with same transactionId (should throw CONFLICT) ...");
        org.junit.jupiter.api.Assertions.assertThrows(
                com.wallet.service.exception.DuplicateTransactionException.class,
                () -> transactionService.process(request)
        );

        // Balance must still be 400 (only one debit)
        Wallet wallet = transactionService.getWallet(userId);
        assertThat(wallet.getBalance()).isEqualByComparingTo("400.00");
        System.out.println("  Balance after 2 calls: ₹" + wallet.getBalance() + " (expected ₹400.00 — deducted only once)");
        System.out.println("  ✅ PASSED — Duplicate request correctly rejected, balance unchanged.");
    }
}
