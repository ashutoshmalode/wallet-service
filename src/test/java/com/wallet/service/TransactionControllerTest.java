package com.wallet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.service.dto.TransactionRequest;
import com.wallet.service.dto.TransactionResponse;
import com.wallet.service.entity.Transaction.TransactionStatus;
import com.wallet.service.entity.Transaction.TransactionType;
import com.wallet.service.exception.DuplicateTransactionException;
import com.wallet.service.exception.InsufficientFundsException;
import com.wallet.service.service.TransactionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TransactionService transactionService;

    @Test
    @DisplayName("POST /api/v1/transactions/process returns 201 CREATED for a valid transaction.")
    void processTransaction_validRequest_returns201() throws Exception {
        System.out.println("\n[Controller Test] Valid transaction → 201 CREATED");

        UUID txId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        TransactionRequest request = TransactionRequest.builder()
                .transactionId(txId)
                .userId(userId)
                .amount(new BigDecimal("250.00"))
                .type(TransactionType.DEBIT)
                .build();

        TransactionResponse response = TransactionResponse.builder()
                .transactionId(txId)
                .userId(userId)
                .amount(new BigDecimal("250.00"))
                .type(TransactionType.DEBIT)
                .status(TransactionStatus.SUCCESS)
                .balanceAfter(new BigDecimal("750.00"))
                .message("Transaction processed successfully")
                .timestamp(LocalDateTime.now())
                .build();

        when(transactionService.process(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/transactions/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.balanceAfter").value(750.00));

        System.out.println("  ✅ PASSED — 201 CREATED returned for valid transaction.");
    }

    @Test
    @DisplayName("POST /api/v1/transactions/process returns 409 CONFLICT for duplicate transactionId.")
    void processTransaction_duplicateId_returns409() throws Exception {
        System.out.println("\n[Controller Test] Duplicate transactionId → 409 CONFLICT");

        UUID txId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        TransactionRequest request = TransactionRequest.builder()
                .transactionId(txId)
                .userId(userId)
                .amount(new BigDecimal("100.00"))
                .type(TransactionType.DEBIT)
                .build();

        TransactionResponse original = TransactionResponse.builder()
                .transactionId(txId)
                .userId(userId)
                .amount(new BigDecimal("100.00"))
                .status(TransactionStatus.SUCCESS)
                .timestamp(LocalDateTime.now())
                .build();

        when(transactionService.process(any()))
                .thenThrow(new DuplicateTransactionException(original));

        mockMvc.perform(post("/api/v1/transactions/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());

        System.out.println("  ✅ PASSED — 409 CONFLICT returned for duplicate transactionId.");
    }

    @Test
    @DisplayName("POST /api/v1/transactions/process returns 422 for insufficient funds.")
    void processTransaction_insufficientFunds_returns422() throws Exception {
        System.out.println("\n[Controller Test] Insufficient funds → 422 UNPROCESSABLE_ENTITY");

        TransactionRequest request = TransactionRequest.builder()
                .transactionId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .amount(new BigDecimal("9999.00"))
                .type(TransactionType.DEBIT)
                .build();

        when(transactionService.process(any()))
                .thenThrow(new InsufficientFundsException("Insufficient funds"));

        mockMvc.perform(post("/api/v1/transactions/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity());

        System.out.println("  ✅ PASSED — 422 returned for insufficient funds.");
    }

    @Test
    @DisplayName("POST /api/v1/transactions/process returns 400 BAD REQUEST when required fields are missing.")
    void processTransaction_missingFields_returns400() throws Exception {
        System.out.println("\n[Controller Test] Missing fields → 400 BAD REQUEST");

        String badJson = "{ \"amount\": 100.00 }";  // Missing transactionId, userId, type

        mockMvc.perform(post("/api/v1/transactions/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isBadRequest());

        System.out.println("  ✅ PASSED — 400 BAD REQUEST for missing required fields.");
    }
}
