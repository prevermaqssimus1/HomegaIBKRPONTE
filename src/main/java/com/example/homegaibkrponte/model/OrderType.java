package com.example.homegaibkrponte.model;

/**
 * PONTE: Define os Tipos de Ordem fundamentais, em alinhamento com a TWS API.
 * A responsabilidade primária é mapear 1:1 com os tipos aceitos pela IBKR.
 * Tipos compostos (como SELL_STOP_LOSS) devem ser resolvidos na lógica do Principal.
 */
// package com.example.homegaibkrponte.model;
/**
 * PONTE: Define os Tipos de Ordem fundamentais, em alinhamento com a TWS API.
 * A responsabilidade primária é mapear 1:1 com os tipos aceitos pela IBKR.
 * Tipos compostos (como SELL_STOP_LOSS) devem ser resolvidos na lógica do Principal.
 */
public enum OrderType {
    MKT,   // Market Order (A mercado)
    LMT,   // Limit Order (Limitada)
    STP,   // Stop Order (Stop)
    TRAIL, // Trailing Stop (Stop Móvel)
    // ... outros tipos nativos da IBKR
    UNKNOWN // Para tratamento de erro/default
}