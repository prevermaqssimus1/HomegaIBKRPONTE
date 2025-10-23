package com.example.homegaibkrponte.dto;

import java.math.BigDecimal;

/**
 * DTO (Data Transfer Object) que representa os dados de uma execução de ordem.
 * Este objeto será enviado via webhook para a aplicação principal.
 */
public record ExecutionReportDTO(
        int orderId,
        String symbol,
        String side, // "BOT" (Bought) ou "SLD" (Sold)
        BigDecimal filledQuantity,
        double avgFillPrice,
        String status // Ex: "Filled", "Cancelled"
) {}