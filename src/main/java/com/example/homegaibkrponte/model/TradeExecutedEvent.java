package com.example.homegaibkrponte.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Evento de Domínio (Padrão de Projeto: Domain Event).
 * Representa a execução de uma negociação (trade) que acabou de acontecer.
 * Este evento é a "fonte única da verdade" para qualquer alteração no estado do portfólio.
 * É um registro imutável de um fato que ocorreu no sistema.
 */
public record TradeExecutedEvent(
        String symbol,
        String side,           // "BUY" ou "SELL"
        BigDecimal quantity,
        BigDecimal price,
        LocalDateTime timestamp,
        String executionSource, // "PAPER" (para backtest/simulação) ou "LIVE" (para Alpaca real)
        String clientOrderId    // ID único da ordem para rastreamento e depuração
) {}