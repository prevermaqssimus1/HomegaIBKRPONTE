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
        @JsonProperty("symbol") @NotNull(message = "O símbolo (symbol) é obrigatório.") String symbol,

        @JsonProperty("type") @NotNull(message = "O tipo da ordem (type) é obrigatório.") String type,

        @JsonProperty("quantity") @NotNull(message = "A quantidade (quantity) é obrigatória.") BigDecimal quantity,
        @JsonProperty("price") BigDecimal price,
        @JsonProperty("orderId") Integer orderId,
        @JsonProperty("stopLossOrderId") String stopLossOrderId,
        @JsonProperty("takeProfitOrderId") String takeProfitOrderId,
        @JsonProperty("stopLossPrice") BigDecimal stopLossPrice,
        @JsonProperty("takeProfitPrice") BigDecimal takeProfitPrice,
        @JsonProperty("priceRef") BigDecimal priceRef,
        @JsonProperty("limitPrice") BigDecimal limitPrice,
        @JsonProperty("rationale") String rationale,
        @JsonProperty("clientOrderId") @NotNull(message = "O ID do cliente (clientOrderId) é obrigatório.") String clientOrderId,
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

    /**
     * Converte a String 'type' para o Enum interno com segurança.
     * @return OrderTypeEnum ou null caso o mapeamento falhe.
     */
    /**
     * ✅ AJUSTE DE SINERGIA: Mapeia Strings genéricas para o OrderTypeEnum.
     * Resolve o erro 500 ao traduzir "SELL" vindo do Principal para "SELL_MARKET".
     */
    public com.example.homegaibkrponte.model.OrderTypeEnum getTypeAsEnum() {
        try {
            if (this.type == null) return null;

            String normalizedType = this.type.toUpperCase().trim();

            // 🛡️ TRADUTOR DE EMERGÊNCIA:
            // Se o Principal enviar apenas "SELL", mapeamos para SELL_MARKET
            // para garantir que a AMD seja vendida agora e salve a margem.
            if (normalizedType.equals("SELL")) {
                log.warn("⚠️ [SINERGIA] Traduzindo String 'SELL' para Enum SELL_MARKET.");
                return com.example.homegaibkrponte.model.OrderTypeEnum.SELL_MARKET;
            }

            if (normalizedType.equals("BUY")) {
                log.warn("⚠️ [SINERGIA] Traduzindo String 'BUY' para Enum BUY_MARKET.");
                return com.example.homegaibkrponte.model.OrderTypeEnum.BUY_MARKET;
            }

            // Tenta o mapeamento padrão (Ex: "SELL_STOP_LOSS")
            return com.example.homegaibkrponte.model.OrderTypeEnum.valueOf(normalizedType.replace(" ", "_"));

        } catch (IllegalArgumentException e) {
            log.error("❌ [SINERGIA] Falha fatal ao reconhecer tipo: {}. Ordem abortada.", this.type);
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