package com.example.homegaibkrponte.service.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Representa os dados essenciais de uma execução de trade dentro do contexto da Ponte (Fill).
 */
public record TradeExecutionResult(
        String symbol,
        String side, // BUY, SELL, SLD, BOT
        BigDecimal quantity,
        BigDecimal price,
        LocalDateTime executionTime,
        String brokerOrderId,
        String exchange
) {}