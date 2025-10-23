package com.example.homegaibkrponte.model;

import lombok.Getter;

@Getter
public enum OrderType {

    // Ordens de Entrada
    BUY_MARKET(PositionSide.BUY),
    SELL_MARKET(PositionSide.SELL),

    // Ordens de Saída/Fechamento
    BUY_TO_COVER(PositionSide.BUY), // Adicionado pelo usuário

    // 🟢 AJUSTE DE SINERGIA: Tipos necessários para o Bracket Order (SL/TP)

    // TIPOS GERAIS DE PROTEÇÃO (Para mapeamento interno)
    STOP_LOSS(PositionSide.UNKNOWN), // Ordem Stop Loss genérica
    TAKE_PROFIT(PositionSide.UNKNOWN), // Ordem Take Profit genérica

    // TIPOS DE STOP (PROTEÇÃO DE ENTRADAS)
    // Para proteger Long: o SL é uma ordem de Venda a preço STOP.
    SELL_STOP(PositionSide.SELL),
    // Para proteger Short: o SL é uma ordem de Compra a preço STOP.
    BUY_STOP(PositionSide.BUY),

    // TIPOS DE LIMIT (REALIZAÇÃO DE LUCRO)
    // Para realizar lucro em Long: o TP é uma ordem de Venda a preço LIMIT.
    SELL_LIMIT(PositionSide.SELL),
    // Para realizar lucro em Short: o TP é uma ordem de Compra a preço LIMIT.
    BUY_LIMIT(PositionSide.BUY),

    // Tipos Legados (mantenha para compatibilidade se existirem)
    SELL_STOP_LOSS(PositionSide.SELL),
    SELL_TAKE_PROFIT(PositionSide.SELL);

    private final PositionSide side;

    OrderType(PositionSide side) {
        this.side = side;
    }
}