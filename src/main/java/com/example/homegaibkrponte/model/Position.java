package com.example.homegaibkrponte.model;

import com.example.homegaibkrponte.domain.OrderSide;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Modelo de Posição usado na Ponte IBKR.
 * ESTENDE A ESTRUTURA ORIGINAL com campos essenciais para CashQty e Metadados IBKR (conId/Account).
 */
@Getter
@ToString
@Slf4j
public final class Position {

    // --- CAMPOS ORIGINAIS (DO DOMÍNIO PRINCIPAL / CONTRATO DE SINCRONIZAÇÃO) ---
    private final String symbol;
    private final BigDecimal quantity;
    private final BigDecimal averageEntryPrice;
    private final LocalDateTime entryTime;
    private final PositionDirection direction;
    private final BigDecimal stopLoss;
    private final BigDecimal takeProfit;
    private final String rationale;

    // --- ✅ CAMPOS ADICIONAIS IBKR E CASH QTY (PARA EXECUÇÃO NA PONTE) ---
    private final long conId;
    private final String account;
    private final BigDecimal currentMarketPrice;
    private final BigDecimal marketValue;
    private final Map<String, String> contractDetails;
    private final boolean isEmergencyLiquidityCandidate;


    // =========================================================================
    // 1. CONSTRUTOR COMPLETO (USADO PELO BUILDER E JSON/JACKSON)
    // =========================================================================

    @Builder(toBuilder = true)
    @JsonCreator
    public Position(
            // Campos Originais
            @JsonProperty("symbol") String symbol,
            @JsonProperty("quantity") BigDecimal quantity,
            @JsonProperty("averageEntryPrice") BigDecimal averageEntryPrice,
            @JsonProperty("direction") PositionDirection direction,
            @JsonProperty("entryTime") LocalDateTime entryTime,
            @JsonProperty("stopLoss") BigDecimal stopLoss,
            @JsonProperty("takeProfit") BigDecimal takeProfit,
            @JsonProperty("rationale") String rationale,

            // Novos Campos
            @JsonProperty("conId") Long conId,
            @JsonProperty("account") String account,
            @JsonProperty("currentMarketPrice") BigDecimal currentMarketPrice,
            @JsonProperty("marketValue") BigDecimal marketValue,
            @JsonProperty("contractDetails") Map<String, String> contractDetails,
            @JsonProperty("isEmergencyLiquidityCandidate") Boolean isEmergencyLiquidityCandidate
    ) {
        // --- VALIDAÇÃO BÁSICA (PRESERVAÇÃO) ---
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Quantidade deve ser positiva.");
        }

        // --- INICIALIZAÇÃO DE TODOS OS CAMPOS ---
        this.symbol = symbol;
        this.quantity = quantity;
        this.averageEntryPrice = Optional.ofNullable(averageEntryPrice).orElse(BigDecimal.ZERO);
        this.entryTime = Optional.ofNullable(entryTime).orElse(LocalDateTime.now());
        this.direction = Optional.ofNullable(direction).orElse(PositionDirection.LONG);
        this.stopLoss = stopLoss;
        this.takeProfit = takeProfit;
        this.rationale = Optional.ofNullable(rationale).orElse("Posição carregada via API de Teste");

        this.conId = Optional.ofNullable(conId).orElse(0L);
        this.account = account;
        this.currentMarketPrice = currentMarketPrice;
        this.marketValue = marketValue;
        this.contractDetails = contractDetails;
        this.isEmergencyLiquidityCandidate = Optional.ofNullable(isEmergencyLiquidityCandidate).orElse(false);
    }

    // =========================================================================
    // 2. CONSTRUTOR DELEGADO (USADO PELO LivePortfolioService PARA SINERGIA)
    //    Resolve os erros de compilação em LivePortfolioService.java
    // =========================================================================
    /**
     * Construtor de Sinérgico para instâncias de baixo nível no LivePortfolioService (8 argumentos).
     * Delega para o construtor completo, passando valores nulos/default para campos IBKR estendidos.
     */
    public Position(
            String symbol,
            BigDecimal quantity,
            BigDecimal averageEntryPrice,
            LocalDateTime entryTime,
            PositionDirection direction,
            BigDecimal stopLoss,
            BigDecimal takeProfit,
            String rationale)
    {
        this(
                symbol,
                quantity,
                averageEntryPrice,
                direction,
                entryTime,
                stopLoss,
                takeProfit,
                rationale,

                // Novos Campos IBKR (Valores Default para o Construtor Delegado)
                0L,           // conId
                null,         // account
                null,         // currentMarketPrice
                null,         // marketValue
                null,         // contractDetails
                false         // isEmergencyLiquidityCandidate
        );
    }

    // --- CÁLCULO DE VALOR DE MERCADO PARA CASH QTY (ADICIONADO) ---

    /**
     * ✅ Retorna o valor de mercado atual da posição (Cash Quantity).
     */
    public BigDecimal getMarketValue() {
        if (this.marketValue != null && this.marketValue.compareTo(BigDecimal.ZERO) > 0) {
            return this.marketValue.abs();
        }

        BigDecimal price = Optional.ofNullable(this.currentMarketPrice)
                .filter(p -> p.compareTo(BigDecimal.ZERO) > 0)
                .orElse(this.averageEntryPrice);

        if (this.quantity != null && price != null && price.compareTo(BigDecimal.ZERO) > 0) {
            return this.quantity.multiply(price).abs();
        }

        return BigDecimal.ZERO;
    }


    // --- MÉTODOS DE DOMÍNIO ESSENCIAIS (MANTIDOS) ---

    public OrderType getClosingOrderType() {
        return OrderType.MKT;
    }

    public OrderSide getClosingOrderSide() {
        return this.direction == PositionDirection.LONG ?
                OrderSide.SELL : OrderSide.BUY_TO_COVER;
    }

    // Nota: Os getters para os novos campos já são gerados pelo @Getter do Lombok.
}