package com.example.homegaibkrponte.model;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 🌉 PONTE: Modelo de Resposta da Simulação de Margem What-If.
 *
 * Este DTO encapsula o resultado da simulação de margem da Interactive Brokers
 * para uma nova ordem, focando na mudança de margem inicial e manutenção.
 */
@Value
@Builder
// [2025-10-18] Mantendo boas práticas (DTO imutável com Lombok)
public class MarginPreviewResponse {

    // [Principal] Campo chave para o AtrPositionSizingStrategy.
    private final BigDecimal initialMarginChange;

    private final BigDecimal maintenanceMarginChange;
    private final String accountId;
    private final Map<String, BigDecimal> currentMargins; // Margens atuais da conta antes do what-if
    private final String currency;
}