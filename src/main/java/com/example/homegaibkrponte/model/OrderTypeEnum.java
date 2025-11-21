package com.example.homegaibkrponte.model;

import com.ib.client.Types;

/**
 * 🌉 **PONTE | ENUM:** Definição unificada dos tipos de ordem usados no ecossistema da Ponte.
 * Este Enum é a **fonte de verdade** para os tipos de ordem, contendo a intenção do Domínio
 * Principal e os helpers necessários para a API TWS/IBKR.
 *
 * 🚨 COERÊNCIA APRIMORADA: Substituído STOP_LOSS/TAKE_PROFIT genéricos por tipos de SAÍDA explícitos (SELL).
 */
public enum OrderTypeEnum {
    // Ordens de Execução Imediata
    BUY_MARKET,
    SELL_MARKET,

    // Ordens Limite
    BUY_LIMIT,
    SELL_LIMIT,

    // Ordens Stop (Gatilho de Entrada)
    BUY_STOP,
    SELL_STOP,

    // Ordens de Proteção/Saída de Long (SELL é a ação implícita)
    SELL_STOP_LOSS,     // ✅ Novo: Ordem Stop para proteger posição Longa
    SELL_TAKE_PROFIT,   // ✅ Novo: Ordem Limite para realizar lucro em posição Longa

    // Ordens de Proteção/Saída de Short (BUY é a ação implícita)
    BUY_STOP_LOSS,      // ✅ Novo: Ordem Stop para proteger posição Short
    BUY_TAKE_PROFIT,    // ✅ Novo: Ordem Limite para realizar lucro em posição Short

    // Tipo de Domínio para fechar posições vendidas a descoberto
    BUY_TO_COVER,

    // Alias de Decisão de Resgate Inteligente (MKT e LMT Puros)
    MKT,
    LMT;

    /**
     * Helper: Checa se é uma ordem de execução imediata a preço de mercado.
     */
    public boolean isMarketOrder() {
        return this == BUY_MARKET || this == SELL_MARKET || this == MKT;
    }

    /**
     * Helper: Checa se é uma ordem limite.
     */
    public boolean isLimitOrder() {
        return this == BUY_LIMIT || this == SELL_LIMIT || this == LMT ||
                this == SELL_TAKE_PROFIT || this == BUY_TAKE_PROFIT;
    }

    /**
     * Helper: Checa se é uma ordem stop (aguarda preço de gatilho).
     */
    public boolean isStopOrder() {
        return this == BUY_STOP || this == SELL_STOP ||
                this == SELL_STOP_LOSS || this == BUY_STOP_LOSS;
    }

    /**
     * ✅ **CRÍTICO:** Retorna a ação (BUY ou SELL) para o TWS.
     * A lógica é baseada na ação implícita do tipo de ordem.
     */
    public String getSide() {
        // Se a ordem é explicitamente de COMPRA, Cobertura ou de Saída de Short
        if (this.name().contains("BUY")) {
            return Types.Action.BUY.name(); // Retorna "BUY" para o TWS
        }
        // Se a ordem é explicitamente de VENDA ou de Saída de Long
        else if (this.name().contains("SELL")) {
            return Types.Action.SELL.name(); // Retorna "SELL" para o TWS
        }
        // Fallback para MKT/LMT puros (depende do contexto de entrada/saída, mas o TWS exige um valor)
        // Por padrão, é mais seguro forçar a revisão do código se cair aqui.
        return Types.Action.SELL.name();
    }

    /**
     * ✅ **SINERGIA:** Retorna o Tipo de Ordem TWS (Ex: MKT, LMT, STP).
     */
    public String getOrderType() {
        if (this.name().contains("MARKET") || this == MKT) return "MKT";
        if (this.name().contains("LIMIT") || this == LMT) return "LMT";
        // STOP é mapeado para STP
        if (this.name().contains("STOP")) return "STP";
        // TAKE_PROFIT (Realização de Lucro) é sempre uma ordem Limite (LMT) no TWS
        if (this.name().contains("PROFIT")) return "LMT";
        // BUY_TO_COVER é uma ordem de cobertura de Short, geralmente tratada como uma Limit ou Market,
        // mas aqui vamos tratar como Market (MKT) para simplicidade na ponte.
        if (this == BUY_TO_COVER) return "MKT";

        return "UNKNOWN";
    }
}