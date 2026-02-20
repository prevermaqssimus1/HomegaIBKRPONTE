package com.example.homegaibkrponte.model;

import com.example.homegaibkrponte.domain.OrderSide;

import java.math.BigDecimal;
import java.math.RoundingMode;

// Importar os Enums necessários (ajuste o caminho se necessário, e.g., OrderSide)
// Assumindo que OrderSide e OrderType estão acessíveis no projeto da Ponte.
// import com.example.homegaibkrponte.model.enums.OrderSide;
// import com.example.homegaibkrponte.model.enums.OrderType;

/**
 * Record que representa uma Ordem de Negociação.
 * ESTRUTURA COMPLETA E IMUTÁVEL: Contrato Final para o trânsito Principal <-> Ponte.
 */
public record Order(
        String symbol,
        OrderSide side, // ✅ CAMPO ESSENCIAL ADICIONADO PARA RESOLVER O ERRO DE COMPILAÇÃO
        OrderType type,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal stopLossPrice,
        BigDecimal takeProfitPrice,
        String rationale,
        String clientOrderId
) {
    // Construtor principal completo, ajustado para incluir 'side'
    public Order(String symbol, OrderSide side, OrderType type, BigDecimal quantity, BigDecimal price, BigDecimal stopLossPrice, BigDecimal takeProfitPrice, String rationale, String clientOrderId) {
        this.symbol = symbol;
        this.side = side; // <--- AGORA INICIALIZADO
        this.type = type;
        this.quantity = quantity;
        this.price = price;
        this.stopLossPrice = stopLossPrice;
        this.takeProfitPrice = takeProfitPrice;
        this.rationale = rationale;
        this.clientOrderId = clientOrderId;
    }

    // <<< Construtores auxiliares devem ser atualizados para incluir OrderSide >>>

    /**
     * Retorna o custo estimado da ordem.
     */
    public BigDecimal getEstimatedCost() {
        if (this.quantity == null || this.price == null) {
            return BigDecimal.ZERO;
        }

        // Lógica de Venda/Compra usando 'side' (o campo que causava o erro de compilação)
        if (this.side == OrderSide.SELL || this.side == OrderSide.BUY_TO_COVER) {
            return BigDecimal.ZERO; // Vendas liberam liquidez
        }

        return this.quantity.abs().multiply(this.price).setScale(2, RoundingMode.HALF_UP);
    }

    public boolean isVenda() {
        return this.side == OrderSide.SELL || this.side == OrderSide.SELL_SHORT;
    }

    /**
     * ✅ MÉTODO COMPLEMENTAR: Identifica entradas.
     */
    public boolean isCompra() {
        return this.side == OrderSide.BUY || this.side == OrderSide.BUY_TO_COVER;
    }

    /**
     * Retorna uma nova instância de Order com o novo clientOrderId.
     */
    public Order withClientOrderId(String newClientOrderId) {
        return new Order(
                this.symbol,
                this.side, // ✅ Incluído
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