package com.example.homegaibkrponte.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Ordem de Compra: Representa uma das ordens fragmentadas geradas pelo Divisor.
 * É a saída do DivisorDeOrdens e a entrada da Ponte (para envio ao broker).
 */
public record OrdemCompra(
        String ativo,
        BigDecimal custoPorOrdem, // O valor monetário desta ordem específica
        BigDecimal quantidade,
        BigDecimal precoMax,
        UUID transactionId
) {}