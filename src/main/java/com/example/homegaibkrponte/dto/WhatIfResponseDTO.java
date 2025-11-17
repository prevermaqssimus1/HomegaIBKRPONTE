package com.example.homegaibkrponte.dto;

import lombok.Value;
import java.math.BigDecimal;

/**
 * DTO de Resposta REST para a simulação What-If (Imutável).
 * Inclui o Real-Time Buying Power (Liquidez) para validação no Principal,
 * conforme as regras de negócio para evitar o Modo de Resgate de Emergência.
 */
@Value // Gera construtor com todos os argumentos, getters, toString, hashCode e equals.
public class WhatIfResponseDTO {

    // Campos são final por padrão com @Value
    boolean success;
    BigDecimal initialMarginChange;

    // 📢 NOVO CAMPO CHAVE: Liquidez atualizada em tempo real.
    // O Controller da Ponte será responsável por buscar este valor (fora do cache, se necessário)
    // e preenchê-lo na resposta.
    BigDecimal realTimeBuyingPower;

    String errorMessage;

}