package com.example.homegaibkrponte.service.order;

import java.math.BigDecimal;

import com.example.homegaibkrponte.domain.OrderSide;
import lombok.Builder;
import lombok.Value;

/**
 * Contrato de Ordem de Resgate de Emergência (Liquidação).
 * Usado pelo Domínio Principal para instruir a Ponte.
 */
@Value
@Builder
public class EmergencyOrder {
    String clientOrderId;
    String symbol;
    OrderSide side;
    BigDecimal quantity;
    String rationale;
    String clientId;
}