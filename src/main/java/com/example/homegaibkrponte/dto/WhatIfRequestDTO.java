package com.example.homegaibkrponte.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO de Requisição REST para a simulação What-If (Mutável).
 * Gera getters, setters, construtor padrão e construtor com todos os argumentos.
 */
@Data // Gera Getters, Setters, toString, equals, hashCode, e AllArgsConstructor
@NoArgsConstructor // Necessário para a deserialização JSON (Binding)
public class WhatIfRequestDTO {

    // Não são finais para permitir setters e deserialização
    private String symbol;
    private int quantity;
    private String side; // Ex: "BUY" ou "SELL"

    // O Lombok gera o construtor padrão (NoArgsConstructor) e o construtor completo (@Data + @AllArgsConstructor)

}