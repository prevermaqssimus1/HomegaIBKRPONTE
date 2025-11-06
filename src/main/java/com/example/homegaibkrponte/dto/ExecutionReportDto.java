package com.example.homegaibkrponte.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO para representar o Relatório de Execução de uma Ordem.
 * Este objeto é enviado da Ponte (Bridge) para o Principal via Webhook.
 *
 * NOTA: O 'orderId' no contexto da Ponte/Principal é o ID gerado pelo sistema principal (clientId),
 * não o permId do IBKR.
 */
public class ExecutionReportDto {

    private final long orderId; // O 'clientId' usado na Ponte, que é o Order ID do Principal.
    private final String symbol;
    private final String side; // "BUY" ou "SELL"
    private final int quantity;
    private final BigDecimal price;
    private final LocalDateTime executionTime;
    private final String ibkrExecId; // ID de execução fornecido pelo IBKR

    // Construtor com todos os campos (Imutabilidade)
    public ExecutionReportDto(long orderId, String symbol, String side, int quantity, BigDecimal price, LocalDateTime executionTime, String ibkrExecId) {
        this.orderId = orderId;
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.executionTime = executionTime;
        this.ibkrExecId = ibkrExecId;
    }

    // Getters (Apenas getters são necessários para um DTO de envio)
    public long getOrderId() {
        return orderId;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getSide() {
        return side;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public LocalDateTime getExecutionTime() {
        return executionTime;
    }

    public String getIbkrExecId() {
        return ibkrExecId;
    }

    // Opcional: toString para logs
    @Override
    public String toString() {
        return "ExecutionReportDto{" +
                "orderId=" + orderId +
                ", symbol='" + symbol + '\'' +
                ", side='" + side + '\'' +
                ", quantity=" + quantity +
                ", price=" + price +
                ", executionTime=" + executionTime +
                ", ibkrExecId='" + ibkrExecId + '\'' +
                '}';
    }
}