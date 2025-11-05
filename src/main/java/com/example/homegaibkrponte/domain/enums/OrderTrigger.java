package com.example.homegaibkrponte.domain.enums;

/**
 * Define a causa ou o gatilho que originou uma ordem.
 */
public enum OrderTrigger {
    STRATEGY_SIGNAL, // Ordem de entrada gerada por um sinal de estratégia.
    STOP_LOSS,       // Ordem de saída gerada por um gatilho de stop-loss.
    TAKE_PROFIT,     // Ordem de saída gerada por um gatilho de take-profit.
    MANUAL_INTERVENTION, // Futuro: Ordem iniciada manualmente.
    PORTFOLIO_LIQUIDATION, // Ordem gerada por um circuit breaker global.
    MANUAL_OVERRIDE,
    EMERGENCY_LIQUIDITY
}