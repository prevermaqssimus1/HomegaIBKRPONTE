package com.example.homegaibkrponte.dto;

// 🚨 CORRIGINDO O NOME DO ENUM (Sinergia de Modelos)
import com.example.homegaibkrponte.model.OrderTypeEnum;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Data Transfer Object (DTO) que reflete a estrutura da ordem.
 * AJUSTADO: Restaurados 14 campos para a lógica de Resgate Inteligente e corrigido o tipo de Enum.
 */
public record OrderDTO(
        // CAMPOS PRINCIPAIS
        @JsonProperty("symbol") @NotNull(message = "O símbolo (symbol) é obrigatório.") String symbol,
        // CORREÇÃO CRÍTICA: Mudar para OrderTypeEnum
        @JsonProperty("type") @NotNull(message = "O tipo da ordem (type) é obrigatório e não pode ser nulo.") OrderTypeEnum type,
        @JsonProperty("quantity") @NotNull(message = "A quantidade (quantity) é obrigatória.") BigDecimal quantity,
        @JsonProperty("price") BigDecimal price, // Usado como preço de trigger/entrada

        @JsonProperty("orderId") Integer orderId,

        // CAMPOS DE PROTEÇÃO (SL/TP)
        @JsonProperty("stopLossOrderId") String stopLossOrderId,
        @JsonProperty("takeProfitOrderId") String takeProfitOrderId,
        @JsonProperty("stopLossPrice") BigDecimal stopLossPrice,
        @JsonProperty("takeProfitPrice") BigDecimal takeProfitPrice,

        // 🚨 CAMPOS CRÍTICOS RESTAURADOS/ADICIONADOS (10º e 11º campos)
        @JsonProperty("priceRef") BigDecimal priceRef,
        @JsonProperty("limitPrice") BigDecimal limitPrice,

        // METADADOS
        @JsonProperty("rationale") String rationale,
        @JsonProperty("clientOrderId")
        @NotNull(message = "O ID da Ordem do Cliente (clientOrderId) é obrigatório para rastreamento.") String clientOrderId,

        @JsonProperty("childOrders") List<OrderDTO> childOrders
) {
    // Construtor Canônico (Usado pelo Jackson para desserialização)
    public OrderDTO {
        childOrders = Optional.ofNullable(childOrders).orElse(Collections.emptyList());
    }

    // --- MÉTODOS HELPERS (Adaptados para 14 Campos) ---

    /**
     * Helper para criar uma NOVA instância de OrderDTO com o orderId preenchido.
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
     * Helper para clonar o DTO com o Tipo e Preço Limite alterados. (Para Resgate MKT -> LMT)
     */
    public OrderDTO withTypeAndLimitPrice(OrderTypeEnum newType, BigDecimal newLimitPrice) {
        return new OrderDTO(
                this.symbol, newType, this.quantity, this.price, this.orderId,
                this.stopLossOrderId, this.takeProfitOrderId, this.stopLossPrice,
                this.takeProfitPrice, this.priceRef, newLimitPrice, // <-- Limite Price Injetado
                this.rationale, this.clientOrderId, this.childOrders
        );
    }

    /**
     * Helper para criar uma NOVA instância de OrderDTO com a lista de childOrders atualizada.
     */
    public OrderDTO withChildOrders(List<OrderDTO> newChildOrders) {
        return new OrderDTO(
                this.symbol, this.type, this.quantity, this.price, this.orderId,
                this.stopLossOrderId, this.takeProfitOrderId, this.stopLossPrice,
                this.takeProfitPrice, this.priceRef, this.limitPrice, this.rationale,
                this.clientOrderId, newChildOrders // <-- NOVA LISTA INJETADA
        );
    }

    // --- MÉTODOS DE ACESSO CRÍTICOS (RESOLVEM COMPILAÇÃO) ---
    public BigDecimal priceRef() { return priceRef; }
    public BigDecimal limitPrice() { return limitPrice; }

    public boolean isBracketOrder() {
        return !childOrders.isEmpty();
    }

    public boolean isStopLoss() {
        return this.type != null && this.type.name().contains("STOP_LOSS");
    }

    public boolean isTakeProfit() {
        return this.type != null && this.type.name().contains("TAKE_PROFIT");
    }
}