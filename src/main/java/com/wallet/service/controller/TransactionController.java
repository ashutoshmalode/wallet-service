package com.wallet.service.controller;

import com.wallet.service.dto.TransactionRequest;
import com.wallet.service.dto.TransactionResponse;
import com.wallet.service.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping("/process")
    public ResponseEntity<TransactionResponse> processTransaction(
            @Valid @RequestBody TransactionRequest request) {

        log.info("Received transaction request: transactionId={}, userId={}, amount={}, type={}",
                request.getTransactionId(), request.getUserId(), request.getAmount(), request.getType());

        TransactionResponse response = transactionService.process(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
