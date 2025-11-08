package com.example.homegaibkrponte.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * PONTE: Data Transfer Object para as métricas críticas de Margem da IBKR.
 * Usado para comunicação REST entre a Ponte e o Principal.
 */
@Getter
@Setter
@ToString
public class MarginMetricsDTO {

    private BigDecimal excessLiquidity;
    private BigDecimal initMarginReq;
    private BigDecimal equityWithLoanValue;

    // Campo crítico retornado pela simulação WhatIf: TRUE se a margem está segura.
    private boolean isMarginSafe;

    public MarginMetricsDTO() {
        this.excessLiquidity = BigDecimal.ZERO;
        this.initMarginReq = BigDecimal.ZERO;
        this.equityWithLoanValue = BigDecimal.ZERO;
        this.isMarginSafe = false;
    }
}