package com.example.homegaibkrponte.dto;

import java.math.BigDecimal;

/**
 * DTO para encapsular a resposta da simulação de margem 'What-If'.
 * **Expandido e corrigido para compatibilidade total com o modelo do Principal.**
 * Este DTO garante sinergia e tipagem entre Principal e Ponte.
 */
public record MarginWhatIfResponseDTO(
        String symbol,
        BigDecimal quantity, // ✅ CORRIGIDO: Agora usa BigDecimal para sinergia e compilação
        BigDecimal initialMarginChange,
        BigDecimal maintenanceMarginChange,
        BigDecimal commissionEstimate,
        String currency,
        String error
) {
    // Construtor principal implícito para records
}