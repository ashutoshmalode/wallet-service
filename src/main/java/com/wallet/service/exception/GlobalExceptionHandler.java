package com.wallet.service.exception;

import com.wallet.service.dto.TransactionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DuplicateTransactionException.class)
    public ResponseEntity<TransactionResponse> handleDuplicate(DuplicateTransactionException ex) {
        log.warn("Duplicate transaction detected: {}", ex.getMessage());
        TransactionResponse body = ex.getOriginalResponse();
        body.setMessage("Duplicate request — original response returned");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<TransactionResponse> handleInsufficientFunds(InsufficientFundsException ex) {
        log.warn("Insufficient funds: {}", ex.getMessage());
        TransactionResponse body = TransactionResponse.builder()
                .status(com.wallet.service.entity.Transaction.TransactionStatus.FAILED)
                .message(ex.getMessage())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(WalletNotFoundException.class)
    public ResponseEntity<TransactionResponse> handleWalletNotFound(WalletNotFoundException ex) {
        log.error("Wallet not found: {}", ex.getMessage());
        TransactionResponse body = TransactionResponse.builder()
                .status(com.wallet.service.entity.Transaction.TransactionStatus.FAILED)
                .message(ex.getMessage())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<TransactionResponse> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        TransactionResponse body = TransactionResponse.builder()
                .status(com.wallet.service.entity.Transaction.TransactionStatus.FAILED)
                .message("Validation failed: " + errors)
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<TransactionResponse> handleGeneral(Exception ex) {
        log.error("Unexpected error", ex);
        TransactionResponse body = TransactionResponse.builder()
                .status(com.wallet.service.entity.Transaction.TransactionStatus.FAILED)
                .message("Internal server error: " + ex.getMessage())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
