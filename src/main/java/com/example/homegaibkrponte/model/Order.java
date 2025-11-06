package com.example.homegaibkrponte.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Record que representa uma Ordem de Negociação.
 * Respeita a estrutura da Ponte IBKR (com.example.homegaibkrponte.model).
 * Implementa princípios de imutabilidade (por ser um Record).
 */
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

    /**
     * <<< NOVO CONSTRUTOR PARA ORDENS DE MERCADO (COMPRA OU VENDA) >>>
     * Usado para ordens que não definem SL/TP no momento da criação, como ordens de fechamento.
     * Ele define stopLossPrice e takeProfitPrice como BigDecimal.ZERO para evitar nulos.
     */
    public Order(String symbol, OrderType type, BigDecimal quantity, BigDecimal price, String rationale) {
        this(symbol, type, quantity, price, BigDecimal.ZERO, BigDecimal.ZERO, rationale, null);
    }

    // Construtor auxiliar antigo (mantido para compatibilidade, se necessário)
    public Order(String symbol, OrderType type, BigDecimal quantity, BigDecimal price, BigDecimal stopLossPrice, BigDecimal takeProfitPrice, String rationale) {
        this(symbol, type, quantity, price, stopLossPrice, takeProfitPrice, rationale, null);
    }

    /**
     * Calcula o custo estimado da ordem (o quanto de Buying Power será consumido).
     */
    public BigDecimal getEstimatedCost() {
        if (this.quantity == null || this.price == null) {
            return BigDecimal.ZERO;
        }

        // 🛑 CORREÇÃO DE LÓGICA: Vendas não consomem Buying Power, elas o liberam.
        // Inclui todas as formas de venda ou proteção de venda existentes na enum.
        if (this.type == OrderType.SELL_MARKET ||
                this.type == OrderType.SELL_STOP || // Incluindo o tipo base STOP
                this.type == OrderType.SELL_LIMIT || // Incluindo o tipo base LIMIT
                this.type == OrderType.SELL_STOP_LOSS ||
                this.type == OrderType.SELL_TAKE_PROFIT) {

            return BigDecimal.ZERO;
        }

        // Apenas compras (BUY) e Buy-to-Cover (fechar short) consomem caixa.
        // Não é necessário o try-catch aqui, pois 'record' garante imutabilidade e a checagem de nulo está acima.
        return this.quantity.abs().multiply(this.price).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Determina se a ordem é de LIQUIDAÇÃO ou Redução de Margem.
     * Inclui todas as ordens que vendem (liberando liquidez).
     */
    public boolean isLiquidationOrMarginReducingOrder() {
        // Inclui todas as formas de venda (SELL) e Buy-to-Cover (fechamento de short)
        return this.type == OrderType.SELL_MARKET ||
                this.type == OrderType.SELL_LIMIT ||
                this.type == OrderType.SELL_STOP ||
                // Removido: OrderType.SELL_STOP_LIMIT (Não existe mais)
                this.type == OrderType.SELL_STOP_LOSS ||
                this.type == OrderType.SELL_TAKE_PROFIT ||
                this.type == OrderType.BUY_TO_COVER; // Se você usa short selling
    }

    /**
     * Determina se a ordem é de ENTRADA (Abertura/Aumento de Posição) e, teoricamente, aumenta a exposição.
     * Inclui todas as ordens de COMPRA.
     */
    public boolean isEntryOrMarginIncreasingOrder() {
        return this.type == OrderType.BUY_MARKET ||
                this.type == OrderType.BUY_LIMIT ||
                this.type == OrderType.BUY_STOP;
        // Removido: OrderType.BUY_STOP_LIMIT (Não existe mais)
    }

    /**
     * Retorna uma nova instância de Order (imutabilidade de Record) com o novo clientOrderId.
     */
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