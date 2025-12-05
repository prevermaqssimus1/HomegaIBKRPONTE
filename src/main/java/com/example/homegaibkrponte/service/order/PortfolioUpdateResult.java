package com.example.homegaibkrponte.service.order;

import java.util.Optional;

/**
 * Contrato de Resultado de Execução para a Ponte IBKR.
 * É o modelo de resposta que a Ponte retorna ao orquestrador.
 */
public record PortfolioUpdateResult(
        boolean success,
        String message,
        Optional<TradeExecutionResult> completedExecution
) {
    public static PortfolioUpdateResult success(String message) {
        return new PortfolioUpdateResult(true, message, Optional.empty());
    }

    public static PortfolioUpdateResult failure(String message) {
        return new PortfolioUpdateResult(false, message, Optional.empty());
    }

    public static PortfolioUpdateResult successWithExecution(String message, TradeExecutionResult result) {
        return new PortfolioUpdateResult(true, message, Optional.of(result));
    }
}