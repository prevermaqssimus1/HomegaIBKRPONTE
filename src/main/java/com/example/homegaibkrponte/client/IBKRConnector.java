package com.example.homegaibkrponte.client;

import com.example.homegaibkrponte.model.OrderExecutionResult;

/**
 * 🌉 PONTE: Contrato com a API Nativa (Socket/API IBKR).
 * Mantém a exigência técnica de long para quantidade.
 */
public interface IBKRConnector {

    // Método que a IBKR exige. A quantidade deve ser um long.
    OrderExecutionResult placeOrder(
            String symbol,
            long quantity,
            String action,
            String orderType
    );

    // Método para o disparo via objeto Order (usado no OrderService)
    void placeOrder(String orderId, com.ib.client.Contract contract, com.ib.client.Order order);

    boolean isConnected();
}