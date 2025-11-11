package com.example.homegaibkrponte.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/**
 * DTO para enviar a resposta de margem What-If para o Principal.
 * Deve espelhar o record IBKRMarginPreview do Principal.
 */
@Getter
@Setter
@Builder
public class IBKRMarginPreviewDTO {
    private BigDecimal initialMarginChange;
    private BigDecimal maintenanceMarginChange;
    private BigDecimal commissionEstimate;
    private String currency;
}