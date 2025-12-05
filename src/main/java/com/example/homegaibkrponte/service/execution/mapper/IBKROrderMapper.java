package com.example.homegaibkrponte.service.execution.mapper;

import com.example.homegaibkrponte.model.Position;
import com.example.homegaibkrponte.service.order.EmergencyOrder;
import com.ib.client.Order;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class IBKROrderMapper {

    private long getNextOrderId() { return 21033; }
    private static final String DEFAULT_ORDER_TYPE = "MKT";
    private static final String DEFAULT_TIF = "DAY";
    // Removido o uso direto de DEFAULT_EXCHANGE nesta classe, pois exchange é um campo do CONTRATO
    // public static final String DEFAULT_EXCHANGE = "SMART"; // Apenas para referência, o uso foi movido.

    /**
     * ✅ Mapeia uma ordem de resgate (Domain Intent) para o objeto IBKR (Broker Protocol),
     * utilizando CashQty.
     */
    public Order mapEmergencyOrderToIbkrOrder(EmergencyOrder emergencyOrder, Position targetPosition) {
        Order ibkrOrder = new Order();
        long nextOrderId = getNextOrderId();

        // 1. Validação do Valor da Liquidação (MarketValue)
        BigDecimal liquidacaoTotalValor = targetPosition.getMarketValue();
        if (liquidacaoTotalValor.compareTo(BigDecimal.ZERO) <= 0) {
            log.error("❌ [MAPPER RESGATE] Valor de mercado da posição para {} é zero ou negativo (R$ {}). Veto de ordem.",
                    targetPosition.getSymbol(), liquidacaoTotalValor.toPlainString());
            throw new IllegalArgumentException("Valor da posição inválido para liquidação. Veto de ordem.");
        }

        // 2. Mapeamento de Identificação e Controle
        ibkrOrder.orderId((int) nextOrderId);
        ibkrOrder.permId((int) targetPosition.getConId());
        ibkrOrder.account(targetPosition.getAccount());
        ibkrOrder.clientId(emergencyOrder.getClientId().hashCode());
        ibkrOrder.orderRef(emergencyOrder.getClientOrderId());

        // 3. Configurações de Execução
        ibkrOrder.action(emergencyOrder.getSide().toString());
        ibkrOrder.orderType(DEFAULT_ORDER_TYPE);
        ibkrOrder.tif(DEFAULT_TIF);

        // 4. Configuração de Roteamento (REMOVIDO: ibkrOrder.exchange(DEFAULT_EXCHANGE);)
        // O roteamento SMART é agora forçado no objeto Contract dentro do IBKRExecutionService.

        // 5. Mapeamento da Quantidade (Cash Quantity)
        ibkrOrder.cashQty(liquidacaoTotalValor.doubleValue());

        log.warn("🚨 [MAPPER RESGATE] Ordem Resgate ({}) mapeada. CashQty (Valor): R$ {}. Preparada para roteamento SMART no Contract.",
                emergencyOrder.getClientOrderId(), liquidacaoTotalValor.toPlainString());

        return ibkrOrder;
    }
}