package com.example.homegaibkrponte.dto;

import com.example.homegaibkrponte.model.OrderTypeEnum;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Data Transfer Object (DTO) para Ordem.
 * Local: com.example.homegaibkrponte.dto.OrderDTO
 * * ESPECIFICAÇÃO: Esta classe atua como ponte de dados.
 * O campo 'type' foi alterado para String para evitar exceções de desserialização
 * direta quando o valor externo não mapeia exatamente ao Enum,
 * mantendo a robustez do sistema.
 */
public record OrderDTO(
        @JsonProperty("symbol") String symbol,
        @JsonProperty("type") String type, // O Principal enviará "SELL_MARKET" ou "SELL"
        @JsonProperty("quantity") BigDecimal quantity,
        @JsonProperty("price") BigDecimal price,
        @JsonProperty("orderId") Integer orderId,
        @JsonProperty("stopLossOrderId") String stopLossOrderId,
        @JsonProperty("takeProfitOrderId") String takeProfitOrderId,
        @JsonProperty("stopLossPrice") BigDecimal stopLossPrice,
        @JsonProperty("takeProfitPrice") BigDecimal takeProfitPrice,
        @JsonProperty("priceRef") BigDecimal priceRef,
        @JsonProperty("limitPrice") BigDecimal limitPrice,
        @JsonProperty("rationale") String rationale,
        @JsonProperty("clientOrderId") String clientOrderId,
        @JsonProperty("childOrders") List<OrderDTO> childOrders
) {
    private static final Logger log = LoggerFactory.getLogger(OrderDTO.class);

    // Construtor Canônico com Garantia de Imutabilidade para childOrders
    public OrderDTO {
        childOrders = Optional.ofNullable(childOrders).map(List::copyOf).orElse(Collections.emptyList());
    }

    // --- MÉTODOS HELPERS (FLUID API) ---

    /**
     * Helper para criar uma NOVA instância com orderId preenchido.
     */
    public OrderDTO withOrderId(Integer newOrderId) {
        return new OrderDTO(
                this.symbol, this.type, this.quantity, this.price, newOrderId,
                this.stopLossOrderId, this.takeProfitOrderId, this.stopLossPrice,
                this.takeProfitPrice, this.priceRef, this.limitPrice, this.rationale,
                this.clientOrderId, this.childOrders
        );
    }

    /**
     * Helper para criar uma NOVA instância com o preço atualizado.
     */
    public OrderDTO withPrice(BigDecimal newPrice) {
        return new OrderDTO(
                this.symbol, this.type, this.quantity, newPrice, this.orderId,
                this.stopLossOrderId, this.takeProfitOrderId, this.stopLossPrice,
                this.takeProfitPrice, this.priceRef, this.limitPrice, this.rationale,
                this.clientOrderId, this.childOrders
        );
    }

    /**
     * Helper para clonar o DTO com o Tipo e Preço Limite alterados.
     * Essencial para a lógica de conversão MKT -> LMT no Resgate.
     */
    public OrderDTO withTypeAndLimitPrice(String newType, BigDecimal newLimitPrice) {
        return new OrderDTO(
                this.symbol, newType, this.quantity, this.price, this.orderId,
                this.stopLossOrderId, this.takeProfitOrderId, this.stopLossPrice,
                this.takeProfitPrice, this.priceRef, newLimitPrice,
                this.rationale, this.clientOrderId, this.childOrders
        );
    }

    /**
     * Helper para atualizar a lista de ordens filhas (Bracket Orders).
     */
    public OrderDTO withChildOrders(List<OrderDTO> newChildOrders) {
        return new OrderDTO(
                this.symbol, this.type, this.quantity, this.price, this.orderId,
                this.stopLossOrderId, this.takeProfitOrderId, this.stopLossPrice,
                this.takeProfitPrice, this.priceRef, this.limitPrice, this.rationale,
                this.clientOrderId, newChildOrders
        );
    }

    // --- MÉTODOS DE LÓGICA E SINERGIA ---

    public com.example.homegaibkrponte.model.OrderTypeEnum getTypeAsEnum() {
        try {
            if (this.type == null) return null;
            String normalizedType = this.type.toUpperCase().trim();

            return switch (normalizedType) {
                // ☢️ TRADUTOR DE EMERGÊNCIA (O aperto de mão que salvamos a conta)
                // Quando o Principal manda apenas a ação, a Ponte assume a execução a Mercado.
                case "BUY" -> com.example.homegaibkrponte.model.OrderTypeEnum.BUY_MARKET;
                case "SELL" -> com.example.homegaibkrponte.model.OrderTypeEnum.SELL_MARKET;

                // 🎯 MAPEAMENTO DE ALIASES EXPLÍCITOS
                case "MKT", "MARKET" -> com.example.homegaibkrponte.model.OrderTypeEnum.MKT;
                case "LMT", "LIMIT"  -> com.example.homegaibkrponte.model.OrderTypeEnum.LMT;

                // ⚖️ TRATAMENTO PADRÃO (Para Enums complexos como SELL_STOP_LOSS)
                default -> com.example.homegaibkrponte.model.OrderTypeEnum.valueOf(normalizedType.replace(" ", "_"));
            };
        } catch (IllegalArgumentException e) {
            log.error("❌ [PONTE-ERRO] Tipo de ordem desconhecido: {}. Verifique a sinergia Principal/Ponte.", this.type);
            return null;
        } catch (Exception e) {
            log.error("❌ [PONTE-FATAL] Falha na conversão de tipo: {}", e.getMessage());
            return null;
        }
    }

    public boolean isBracketOrder() {
        return childOrders != null && !childOrders.isEmpty();
    }

    public boolean isStopLoss() {
        return this.type != null && this.type.contains("STOP_LOSS");
    }

    public boolean isTakeProfit() {
        return this.type != null && this.type.contains("TAKE_PROFIT");
    }

    /**
     * Retorna o preço de referência, garantindo que nunca retorne erro de compilação
     * por falta de campo no Record.
     */
    public BigDecimal getPriceRef() {
        return priceRef;
    }

    /**
     * Retorna o preço limite.
     */
    public BigDecimal getLimitPrice() {
        return limitPrice;
    }
}