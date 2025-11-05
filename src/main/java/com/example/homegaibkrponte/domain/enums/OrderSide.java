package com.example.homegaibkrponte.domain.enums;

/**
 * Define a direção fundamental de uma ordem.
 * Representa a intenção primária da operação no mercado.
 */
public enum OrderSide {
    /**
     * Uma operação de compra, seja para abrir uma posição long ou fechar uma short.
     */
    BUY,

    BUY_TO_COVER,
    /**
     * Uma operação de venda, seja para fechar uma posição long ou abrir uma short.
     */
    SELL
}