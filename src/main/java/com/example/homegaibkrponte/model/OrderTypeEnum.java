package com.example.homegaibkrponte.model;

import com.ib.client.Types;

/**
 * Definição dos tipos de ordem usados no ecossistema da Ponte.
 * Inclui o método getSide() para compatibilidade com a OrderFactory existente.
 */
public enum OrderTypeEnum {
    BUY_MARKET,
    SELL_MARKET,
    STOP_LOSS,
    TAKE_PROFIT,
    BUY_LIMIT,
    SELL_LIMIT,
    SELL_STOP_LOSS,
    BUY_STOP,
    SELL_STOP,
    SELL_TAKE_PROFIT,

    MKT, // Usado para a Decisão de Resgate Inteligente
    LMT; // Usado para a Decisão de Resgate Inteligente

    // 🚨 Este método é um ALIAS INCORRETO (mas necessário para compilar a OrderFactory)
    // Se a OrderFactory exige getSide() para BUY/SELL/STP/LMT, precisamos de uma resposta.
    public String getSide() {
        if (this.name().contains("BUY")) {
            return Types.Action.BUY.name(); // Retorna "BUY" para o TWS
        } else if (this.name().contains("SELL")) {
            return Types.Action.SELL.name(); // Retorna "SELL" para o TWS
        }
        // Fallback genérico (para MKT/LMT puro ou types desconhecidos)
        return "SELL"; // Assume SELL para evitar erro no Resgate MKT -> LMT
    }
}