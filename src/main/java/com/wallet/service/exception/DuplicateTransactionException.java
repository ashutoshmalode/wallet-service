package com.wallet.service.exception;

import com.wallet.service.dto.TransactionResponse;
import lombok.Getter;

@Getter
public class DuplicateTransactionException extends RuntimeException {

    private final TransactionResponse originalResponse;

    public DuplicateTransactionException(TransactionResponse originalResponse) {
        super("Duplicate transactionId: " + originalResponse.getTransactionId());
        this.originalResponse = originalResponse;
    }
}
