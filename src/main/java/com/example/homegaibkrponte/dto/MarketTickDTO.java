package com.example.homegaibkrponte.dto;

import java.math.BigDecimal;

// Record DTO para Ticks de Mercado (Imutável, simples para transferência)
public record MarketTickDTO(
        String symbol,
        BigDecimal price
) {}