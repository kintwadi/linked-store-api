package com.vicinity24.core.linkedstore.api.exception;

import com.vicinity24.core.linkedstore.api.entity.TransactionStatus;
import lombok.Getter;

import java.util.UUID;

@Getter
public class TransactionStateException extends RuntimeException {

    private final UUID transactionId;
    private final TransactionStatus currentStatus;
    private final TransactionStatus expectedStatus;

    public TransactionStateException(String message, UUID transactionId,
                                     TransactionStatus currentStatus, TransactionStatus expectedStatus) {
        super(message);
        this.transactionId = transactionId;
        this.currentStatus = currentStatus;
        this.expectedStatus = expectedStatus;
    }
}
