package com.example.homegaibkrponte.model;

public enum DomainOrderType {
    BUY_MARKET,
    SELL_MARKET,
    BUY_LIMIT,
    SELL_LIMIT,
    BUY_STOP,
    SELL_STOP,
    BUY_TO_COVER, // Usado para recomprar ações vendidas a descoberto
    STOP_LOSS,    // Se for resolvido no Principal
    SELL_STOP_LOSS, // Se for resolvido no Principal
    TAKE_PROFIT,
    SELL_TAKE_PROFIT
    // ...
}