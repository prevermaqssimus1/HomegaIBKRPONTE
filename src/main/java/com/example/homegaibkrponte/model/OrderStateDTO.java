package com.example.homegaibkrponte.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Representa o estado de uma ordem, especialmente os dados de impacto de margem
 * retornados pelo TWS API no callback EWrapper.openOrder().
 * ... (Campos mantidos como Strings para precisão nativa IBKR)
 */
@Data // Gera Getters, Setters, toString, equals e hashCode
@NoArgsConstructor // Gera o construtor padrão
public class OrderStateDTO {

    // Campos de status e margem
    private String status;
    private String initMarginBefore;
    private String maintMarginBefore;
    private String equityWithLoanBefore;

    private String initMarginChange;     // <-- Campo Chave para a 'Mudança Margem Inicial'
    private String maintMarginChange;
    private String equityWithLoanChange;

    private String initMarginAfter;
    private String maintMarginAfter;
    private String equityWithLoanAfter;
    private String excessLiquidityAfter; // <-- Campo Chave para a 'Margem de Bypass' REAL

    // A estrutura é completa para a fase de mapeamento da Ponte.
}