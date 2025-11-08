package com.example.homegaibkrponte.client;

import com.example.homegaibkrponte.model.OrderExecutionResult;

/**
 * PONTE: Contrato com a API Nativa (Socket/API IBKR).
 * Define a exigência do tipo de dado correto (long) para a quantidade.
 */
public interface IBKRConnector {

    // Método que a IBKR exige. A quantidade deve ser um long.
    OrderExecutionResult placeOrder(
            String symbol,
            long quantity, // <--- EXIGÊNCIA FINAL DA CORRETORA (Tipo long)
            String action,
            String orderType
    );
}