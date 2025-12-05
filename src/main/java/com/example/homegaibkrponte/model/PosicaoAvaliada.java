package com.example.homegaibkrponte.model;

import java.math.BigDecimal;

/**
 * DTO de Posição Avaliada. Usado pelo Principal (GDLService)
 * para incorporar dados de avaliação em tempo real (como PnL)
 * ao modelo base da Ponte (Position), mantendo a sinergia
 * e prevenindo quebras no modelo original.
 */
public record PosicaoAvaliada(
        // O objeto Position real e inalterado da Ponte
        Position posicaoBase,

        // O campo necessário para a lógica de GDL
        BigDecimal lucroNaoRealizado,

        // Opcional: Adicionar a Margem Requerida se for usada na decisão de GDL/Risco
        BigDecimal margemRequerida,

        String ativo,
        BigDecimal quantidade,
        BigDecimal precoMedio
) {
    // Construtor canônico é gerado automaticamente pelo record.
    // Garante que o Position da Ponte e o PnL estejam juntos para a avaliação.
}