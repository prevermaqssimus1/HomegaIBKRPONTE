package com.example.homegaibkrponte.model;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant; // Preferindo Instant para eventos de tempo de execução
import java.util.concurrent.ThreadLocalRandom; // Import necessário para o snippet de uso

/**
 * 🌉 **PONTE | MODELO:** Evento disparado pela Ponte para notificar o Principal
 * sobre a execução real de um trade (callback do TWS/IBKR).
 *
 * 🚨 Sinergia: Ajustado para o formato RECORD/BUILDER, com Instant para o tempo,
 * e re-inclusão do campo 'commission' para aderir ao uso no código cliente.
 */
@Builder(toBuilder = true)
public record TradeExecutedEvent(
        // Identificador primário (Geralmente o ID da Ordem IBKR)
        String orderId,
        String symbol,
        String side,           // "BUY" ou "SELL" (Recomendado o uso de ENUM aqui, mas mantido String para sinergia imediata)
        BigDecimal quantity,
        BigDecimal price,
        // 🛑 Re-adicionado para sinergia com o código de uso
        BigDecimal commission,
        // Renomeado de 'timestamp' para 'executionTime' e alterado para Instant
        Instant executionTime,
        String executionSource,
        // Segundo identificador (usado para rastreamento - ID do cliente)
        String clientOrderId
) {
    // Nota: O campo 'commission' foi re-incluído para garantir a sinergia com o
    // código de criação do evento que você forneceu.
}