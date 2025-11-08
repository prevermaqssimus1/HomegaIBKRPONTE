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
import java.util.Optional;

/**
 * Modelo de Posição usado na Ponte IBKR. [cite: 360]
 * Ajuste: O método getClosingOrderType deve retornar o tipo nativo da Ponte (MKT),
 * pois a Ação é resolvida separadamente por getClosingOrderSide().
 */
@Getter
@ToString
@Builder(toBuilder = true)
@Slf4j
public final class Position {

    // --- CAMPOS (MANTIDOS) ---
    private final String symbol;
    private final BigDecimal quantity;
    private final BigDecimal averageEntryPrice;
    private final LocalDateTime entryTime;
    private final PositionDirection direction;
    private final BigDecimal stopLoss;
    private final BigDecimal takeProfit;
    private final String rationale;

    // Construtor principal (uso interno do Builder) - MANTIDO
    public Position(String symbol, BigDecimal quantity, BigDecimal averageEntryPrice, LocalDateTime entryTime, PositionDirection direction, BigDecimal stopLoss, BigDecimal takeProfit, String rationale) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Quantidade deve ser positiva.");
        }
        this.symbol = symbol;
        this.quantity = quantity;
        this.averageEntryPrice = averageEntryPrice;
        this.entryTime = Optional.ofNullable(entryTime).orElse(LocalDateTime.now());
        this.direction = direction;
        this.stopLoss = stopLoss;
        this.takeProfit = takeProfit;
        this.rationale = rationale;
    }

    // ✅ CONSTRUTOR JSON/API (MANTIDO)
    @JsonCreator
    public Position(
            @JsonProperty("symbol") String symbol,
            @JsonProperty("quantity") BigDecimal quantity,
            @JsonProperty("averageEntryPrice") BigDecimal averageEntryPrice,
            @JsonProperty("direction") PositionDirection direction,
            @JsonProperty("entryTime") LocalDateTime entryTime,
            @JsonProperty("stopLoss") BigDecimal stopLoss,
            @JsonProperty("takeProfit") BigDecimal takeProfit,
            @JsonProperty("rationale") String rationale
    ) {
        this(
                symbol,
                Optional.ofNullable(quantity).orElse(BigDecimal.ZERO),
                Optional.ofNullable(averageEntryPrice).orElse(BigDecimal.ZERO),
                entryTime,
                Optional.ofNullable(direction).orElse(PositionDirection.LONG),
                stopLoss,
                takeProfit,
                Optional.ofNullable(rationale).orElse("Posição carregada via API de Teste")
        );
    }

    // --- MÉTODOS DE DOMÍNIO ESSENCIAIS PARA A PONTE ---

    /**
     * ✅ AJUSTE DE SINERGIA: Determina o TIPO de ordem de mercado para fechar a posição.
     * Na Ponte, Market Order (compra ou venda) é sempre OrderType.MKT.
     */
    public OrderType getClosingOrderType() {
        // Retorna o tipo de ordem de mercado da Ponte, sem a Ação (BUY/SELL)
        // Isso elimina a necessidade de SELL_MARKET ou BUY_MARKET na Ponte.
        return OrderType.MKT;
    }

    /**
     * Determina o LADO da ordem para fechar a posição (RESOLVE CÓDIGO 201). [cite: 375]
     * Este método se torna a fonte da Ação (BUY/SELL).
     */
    public OrderSide getClosingOrderSide() {
        // Se LONG -> Vende (SELL)
        // Se SHORT -> Compra para cobrir (BUY_TO_COVER)
        return this.direction == PositionDirection.LONG ?
                OrderSide.SELL : OrderSide.BUY_TO_COVER;
    }

    // --- GETTERS PADRONIZADOS PARA RISCO (MANTIDOS) ---

    public BigDecimal getStopLossPrice() {
        return this.stopLoss;
    }

    public BigDecimal getTakeProfitPrice() {
        return this.takeProfit;
    }

    public String getSymbol() {
        return this.symbol;
    }

    public BigDecimal getQuantity() {
        return this.quantity;
    }

    public BigDecimal getAverageEntryPrice() {
        return this.averageEntryPrice;
    }
}