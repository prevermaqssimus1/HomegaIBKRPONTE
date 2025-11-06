package com.example.homegaibkrponte.dto;

import java.time.LocalDateTime;

/**
 * DTO para representar uma rejeição crítica de ordem da corretora.
 *
 * SINERGIA CRÍTICA: Contém um construtor de 3 argumentos para ser
 * utilizado pelo WebhookNotifierService, adicionando o timestamp.
 */
public class OrderRejectionDto {

    private final long orderId; // O 'clientId' usado na Ponte, que é o Order ID do Principal.
    private final int errorCode;
    private final String errorMessage;
    private final LocalDateTime rejectionTime;

    // CONSTRUTOR DE SINERGIA: Chamado pelo WebhookNotifierService (3 argumentos)
    public OrderRejectionDto(long orderId, int errorCode, String errorMessage) {
        // Delega para o construtor principal, adicionando o timestamp atual
        this(orderId, errorCode, errorMessage, LocalDateTime.now());
    }

    // CONSTRUTOR PRINCIPAL (4 argumentos - O DTO padrão)
    public OrderRejectionDto(long orderId, int errorCode, String errorMessage, LocalDateTime rejectionTime) {
        this.orderId = orderId;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.rejectionTime = rejectionTime;
    }

    // Getters (Apenas getters são necessários para um DTO de envio)
    public long getOrderId() {
        return orderId;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public LocalDateTime getRejectionTime() {
        return rejectionTime;
    }

    // Opcional: toString para logs
    @Override
    public String toString() {
        return "OrderRejectionDto{" +
                "orderId=" + orderId +
                ", errorCode=" + errorCode +
                ", errorMessage='" + errorMessage + '\'' +
                ", rejectionTime=" + rejectionTime +
                '}';
    }
}