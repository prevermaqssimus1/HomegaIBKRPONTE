package com.example.homegaibkrponte.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

@Getter
@ToString
@Builder(toBuilder = true)
public final class Position {

    private final String symbol;
    private final BigDecimal quantity;
    private final BigDecimal averageEntryPrice;
    private final LocalDateTime entryTime;
    private final PositionDirection direction;
    private final BigDecimal stopLoss;
    private final BigDecimal takeProfit;
    private final String rationale;

    public Position(String symbol, BigDecimal quantity, BigDecimal averageEntryPrice, LocalDateTime entryTime, PositionDirection direction, BigDecimal stopLoss, BigDecimal takeProfit, String rationale) {
        this.symbol = symbol;
        this.quantity = quantity;
        this.averageEntryPrice = averageEntryPrice;
        this.entryTime = entryTime;
        this.direction = direction;
        this.stopLoss = stopLoss;
        this.takeProfit = takeProfit;
        this.rationale = rationale;
    }

    @JsonCreator
    public Position(
            @JsonProperty("symbol") String symbol,
            @JsonProperty("quantity") BigDecimal quantity, // Corrigido para corresponder ao JSON de teste
            @JsonProperty("averageEntryPrice") BigDecimal averageEntryPrice, // Corrigido
            @JsonProperty("direction") PositionDirection direction, // Corrigido
            // Os campos abaixo podem ou não estar no JSON de teste, mas são mapeados se existirem
            @JsonProperty("entryTime") LocalDateTime entryTime,
            @JsonProperty("stopLoss") BigDecimal stopLoss,
            @JsonProperty("takeProfit") BigDecimal takeProfit,
            @JsonProperty("rationale") String rationale
    ) {
        this(
                symbol,
                Optional.ofNullable(quantity).orElse(BigDecimal.ZERO),
                Optional.ofNullable(averageEntryPrice).orElse(BigDecimal.ZERO),
                Optional.ofNullable(entryTime).orElse(LocalDateTime.now()),
                Optional.ofNullable(direction).orElse(PositionDirection.LONG), // Define LONG como padrão se nulo
                stopLoss,
                takeProfit,
                Optional.ofNullable(rationale).orElse("Posição carregada via API de Teste")
        );
    }

    public OrderType getClosingOrderType() {
        return this.direction == PositionDirection.LONG ? OrderType.SELL_MARKET : OrderType.BUY_TO_COVER;
    }
}