package com.wallet.service.dto;

import com.wallet.service.entity.Transaction.TransactionStatus;
import com.wallet.service.entity.Transaction.TransactionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionResponse {

    private UUID transactionId;
    private UUID userId;
    private BigDecimal amount;
    private TransactionType type;
    private TransactionStatus status;
    private BigDecimal balanceAfter;
    private String message;
    private LocalDateTime timestamp;
}
