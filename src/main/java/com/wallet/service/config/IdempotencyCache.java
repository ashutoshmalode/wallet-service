package com.wallet.service.config;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class IdempotencyCache {

    private final ConcurrentHashMap<UUID, Object> inFlight = new ConcurrentHashMap<>();
    private static final Object SENTINEL = new Object();

    public boolean tryAcquire(UUID transactionId) {
        return inFlight.putIfAbsent(transactionId, SENTINEL) == null;
    }

    public void release(UUID transactionId) {
        inFlight.remove(transactionId);
    }
}
