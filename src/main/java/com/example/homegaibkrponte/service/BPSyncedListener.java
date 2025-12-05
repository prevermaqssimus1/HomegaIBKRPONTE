package com.example.homegaibkrponte.service;

import java.math.BigDecimal;

/**
 * Contrato de Callback: Define como a Ponte (IBKR) notifica o Principal
 * de forma assíncrona sobre a sincronização de novos dados de liquidez.
 */
public interface BPSyncedListener {
    /**
     * Chamado pela Ponte quando o BP, NLV e Margem de Reserva são atualizados.
     * @param novoBp Novo Buying Power (Poder de Compra)
     * @param novoNlv Novo Net Liquidation Value (Patrimônio Líquido)
     * @param novaReserveMarginFrac Nova Margem de Reserva (como fração do NLV)
     */
    void onBPSynced(BigDecimal novoBp, BigDecimal novoNlv, BigDecimal novaReserveMarginFrac);
}