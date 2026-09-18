package com.wallet.service.exception;

import java.util.UUID;

public class WalletNotFoundException extends RuntimeException {

    public WalletNotFoundException(UUID userId) {
        super("Wallet not found for userId: " + userId);
    }
}
