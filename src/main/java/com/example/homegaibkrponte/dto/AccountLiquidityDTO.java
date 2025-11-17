package com.example.homegaibkrponte.dto;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Contrato de Serviço que garante a transferência de todas as métricas críticas de liquidez
 * da Ponte para o Principal.
 * NLV (Net Liquidation Value) é a Fonte Única de Verdade (SSOT) para o Poder de Compra real.
 */
@Getter // Necessário para serialização
@NoArgsConstructor // Necessário para desserialização Jackson/WebClient
@AllArgsConstructor // Construtor completo para facilitar testes e criação
public class AccountLiquidityDTO {

    // NLV: Patrimônio Líquido (PL). DEVE ser o valor prioritário.
    @JsonProperty("netLiquidationValue")
    private BigDecimal netLiquidationValue;

    // Saldo em dinheiro. Usado para referência ou fallback seguro.
    @JsonProperty("cashBalance")
    private BigDecimal cashBalance;

    // O Poder de Compra final calculado pela Ponte (Ex: NLV ou Excess Liquidity).
    @JsonProperty("currentBuyingPower")
    private BigDecimal currentBuyingPower;

    // * Adicione outros getters se necessário *
}