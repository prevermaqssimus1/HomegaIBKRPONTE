package com.example.homegaibkrponte.model;

import lombok.Builder;
import lombok.Data;
import lombok.Value;

import java.math.BigDecimal;

/**
 * 🌉 **PONTE | MODELO:** DTO imutável que representa uma intenção de ordem de trade.
 * Este é o contrato de comunicação principal entre o Principal (Orquestrador) e a Ponte (Executor).
 *
 * NOTA: O OrderId é tratado como mutável (via setter) para ser atribuído pelo Principal
 * atomicamente antes do envio para a corretora (TWS/Gateway IBKR).
 */
@Value
@Builder(toBuilder = true)
@Data
public class OrderCommand {

    // Identificador único da ordem atribuído pelo Principal (Atomicamente)
    private long orderId;

    // Símbolo do ativo (Ticker)
    private String symbol;

    // Ação: "BUY", "SELL", "SSHORT" (Short Sell)
    private String action;

    // Tipo de ordem: "LMT" (Limit), "MKT" (Market), "STP" (Stop)
    private String orderType;

    // Quantidade de ações a negociar
    private BigDecimal quantity;

    // Preço limite (obrigatório para LMT/STP)
    private BigDecimal limitPrice;

    // Preço stop (obrigatório para STP)
    private BigDecimal stopPrice;

    // Validade: "DAY" (Day Order), "GTC" (Good Till Cancel)
    private String timeInForce;

    // Detalhes adicionais de roteamento, se necessário
    private String exchange;



}