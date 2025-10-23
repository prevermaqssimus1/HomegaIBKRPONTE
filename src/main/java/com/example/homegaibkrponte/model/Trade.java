package com.example.homegaibkrponte.model;

import com.example.homegaibkrponte.model.PositionDirection;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Representa uma operação de trading concluída (compra e venda).
 * É um objeto imutável para registo e análise de performance.
 */
public record Trade(
        String symbol,
        PositionDirection direction,
        BigDecimal quantity,
        BigDecimal entryPrice,
        BigDecimal exitPrice,
        LocalDateTime entryTime,
        LocalDateTime exitTime,
        BigDecimal profitAndLoss
) {
}
