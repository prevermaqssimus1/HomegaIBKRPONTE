    package com.example.homegaibkrponte.dto; // PACOTE CORRIGIDO

    import com.example.homegaibkrponte.model.OrderType;
    import com.fasterxml.jackson.annotation.JsonProperty;
    import jakarta.validation.constraints.NotNull; // 🚨 IMPORTAÇÃO CORRIGIDA
    import java.math.BigDecimal;
    import java.util.Collections;
    import java.util.List;
    import java.util.Optional;

    /**
     * Data Transfer Object (DTO) que reflete a estrutura da ordem, adaptada para
     * a comunicação interna da Camada Bridge (Ponte IBKR).
     * 🟢 AJUSTE: O pacote foi corrigido para refletir a estrutura de DTOs de execução.
     * 🔔 Regra Aplicada: Validação de Entrada (Boas Práticas/SOLID) para evitar NullPointer.
     */
    public record OrderDTO(
            // CAMPOS PRINCIPAIS
            @JsonProperty("symbol") @NotNull(message = "O símbolo (symbol) é obrigatório.") String symbol, // Adicionado para robustez
            @JsonProperty("type") @NotNull(message = "O tipo da ordem (type) é obrigatório e não pode ser nulo.") OrderType type, // 🚨 CORREÇÃO CRÍTICA PARA EVITAR NULLPOINTER
            @JsonProperty("quantity") @NotNull(message = "A quantidade (quantity) é obrigatória.") BigDecimal quantity, // Adicionado para robustez
            @JsonProperty("price") BigDecimal price,

            // 🟢 CAMPO CRÍTICO ADICIONADO: ID da ordem da IBKR
            @JsonProperty("orderId") Integer orderId,

            // CAMPOS DE PROTEÇÃO (SL/TP)
            @JsonProperty("stopLossOrderId") String stopLossOrderId,
            @JsonProperty("takeProfitOrderId") String takeProfitOrderId,
            @JsonProperty("stopLossPrice") BigDecimal stopLossPrice,
            @JsonProperty("takeProfitPrice") BigDecimal takeProfitPrice,

            // METADADOS
            @JsonProperty("rationale") String rationale,
            @JsonProperty("clientOrderId")
            @NotNull(message = "O ID da Ordem do Cliente (clientOrderId) é obrigatório para rastreamento.") String clientOrderId, // 🟢 Campo crítico descomentado
            // @JsonProperty("state") OrderState state, // Removido por não ter a classe OrderState

            // O contêiner para ordens filhas (Bracket/OCO)
            @JsonProperty("childOrders") List<OrderDTO> childOrders
    ) {

        // Construtor Canônico (Usado pelo Jackson para desserialização)
        public OrderDTO {
            // Garante que a lista de childOrders não seja nula após a desserialização
            childOrders = Optional.ofNullable(childOrders).orElse(Collections.emptyList());
        }

        /**
         * Helper para criar uma NOVA instância de OrderDTO com o orderId preenchido.
         * Necessário pela imutabilidade do Record.
         */
        public OrderDTO withOrderId(Integer newOrderId) {
            return new OrderDTO(
                    this.symbol,
                    this.type,
                    this.quantity,
                    this.price,
                    newOrderId, // <-- ID INJETADO
                    this.stopLossOrderId,
                    this.takeProfitOrderId,
                    this.stopLossPrice,
                    this.takeProfitPrice,
                    this.rationale,
                    this.clientOrderId,
                    // this.state, // Removido
                    this.childOrders
            );
        }

        /**
         * Helper para criar uma NOVA instância de OrderDTO com a lista de childOrders atualizada.
         * Necessário para o Bracket Order (handleBracketOrder).
         */
        public OrderDTO withChildOrders(List<OrderDTO> newChildOrders) {
            return new OrderDTO(
                    this.symbol,
                    this.type,
                    this.quantity,
                    this.price,
                    this.orderId, // Preserva o orderId atual
                    this.stopLossOrderId,
                    this.takeProfitOrderId,
                    this.stopLossPrice,
                    this.takeProfitPrice,
                    this.rationale,
                    this.clientOrderId,
                    // this.state, // Removido
                    newChildOrders // <-- NOVA LISTA INJETADA
            );
        }

        /**
         * Verifica se a ordem é composta (Bracket Order).
         */
        public boolean isBracketOrder() {
            return !childOrders.isEmpty();
        }

        // Nota: Os métodos isStopLoss/isTakeProfit dependem dos valores exatos do seu OrderType.
        public boolean isStopLoss() {
            return this.type != null && this.type.name().contains("STOP_LOSS");
        }

        public boolean isTakeProfit() {
            return this.type != null && this.type.name().contains("TAKE_PROFIT");
        }
    }