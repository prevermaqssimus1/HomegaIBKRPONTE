package com.example.homegaibkrponte.dto;

import java.time.LocalDateTime;

public class OrderRejectionDto {
    private final String clientOrderId; // 🎯 NOVO: Necessário para o Winston limpar o saldo
    private final long orderId;        // Mantido
    private final int errorCode;       // Mantido
    private final String errorMessage; // Mantido
    private final LocalDateTime rejectionTime;

    // CONSTRUTOR ORIGINAL (Mantém a compatibilidade com o que já funciona)
    public OrderRejectionDto(long orderId, int errorCode, String errorMessage) {
        this.clientOrderId = String.valueOf(orderId); // Fallback
        this.orderId = orderId;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.rejectionTime = LocalDateTime.now();
    }

    // NOVO CONSTRUTOR (Para quando a Ponte sabe o ClientID real do Winston)
    public OrderRejectionDto(String clientOrderId, long orderId, int errorCode, String errorMessage) {
        this.clientOrderId = clientOrderId;
        this.orderId = orderId;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.rejectionTime = LocalDateTime.now();
    }

    // Getters mantidos para não quebrar a serialização
    public String getClientOrderId() { return clientOrderId; }
    public long getOrderId() { return orderId; }
    public int getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public LocalDateTime getRejectionTime() { return rejectionTime; }
}