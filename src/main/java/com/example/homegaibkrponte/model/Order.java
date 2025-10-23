package com.example.homegaibkrponte.model;


import java.math.BigDecimal;
import java.math.RoundingMode;

public record Order(
        String symbol,
        OrderType type,
        BigDecimal quantity,
        BigDecimal price, // Renomeado de entryPrice para um termo mais genérico
        BigDecimal stopLossPrice,
        BigDecimal takeProfitPrice,
        String rationale,
        String clientOrderId
) {
    // Construtor principal (completo) - permanece o mesmo
    // (O Java cria este construtor automaticamente para o record)

    /**
     * <<< NOVO CONSTRUTOR PARA ORDENS DE MERCADO (COMPRA OU VENDA) >>>
     * Usado para ordens que não definem SL/TP no momento da criação, como ordens de fechamento.
     * Ele define stopLossPrice e takeProfitPrice como BigDecimal.ZERO para evitar nulos.
     */
    public Order(String symbol, OrderType type, BigDecimal quantity, BigDecimal price, String rationale) {
        this(symbol, type, quantity, price, BigDecimal.ZERO, BigDecimal.ZERO, rationale, null);
    }

    // Construtor auxiliar antigo (pode ser removido ou mantido se ainda for útil em algum lugar)
    public Order(String symbol, OrderType type, BigDecimal quantity, BigDecimal price, BigDecimal stopLossPrice, BigDecimal takeProfitPrice, String rationale) {
        this(symbol, type, quantity, price, stopLossPrice, takeProfitPrice, rationale, null);
    }

    public BigDecimal getEstimatedCost() {
        if (this.quantity == null || this.price == null) {
            return BigDecimal.ZERO;
        }

        // 🛑 CORREÇÃO FINAL: Vendas não consomem Buying Power, elas o liberam.
        if (this.type == OrderType.SELL_MARKET ||
                this.type == OrderType.SELL_STOP_LOSS ||
                this.type == OrderType.SELL_TAKE_PROFIT) {

            return BigDecimal.ZERO;
        }

        // Apenas compras e buy-to-cover (fechar short) consomem caixa.
        return this.quantity.multiply(this.price).setScale(2, RoundingMode.HALF_UP);
    }

    public Order withClientOrderId(String newClientOrderId) {
        return new Order(
                this.symbol,
                this.type,
                this.quantity,
                this.price,
                this.stopLossPrice,
                this.takeProfitPrice,
                this.rationale,
                newClientOrderId
        );
    }
}