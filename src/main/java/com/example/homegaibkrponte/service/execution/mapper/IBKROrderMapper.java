package com.example.homegaibkrponte.service.execution.mapper;

import com.example.homegaibkrponte.model.Position;
import com.example.homegaibkrponte.service.order.EmergencyOrder;
import com.ib.client.Contract;
import com.ib.client.Order;
import com.ib.client.Types;
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

    /**
     * ✅ MANTIDO ORIGINAL: Não alterado para não quebrar o sistema de resgate.
     */
    public Order mapEmergencyOrderToIbkrOrder(EmergencyOrder emergencyOrder, Position targetPosition) {
        Order ibkrOrder = new Order();
        long nextOrderId = getNextOrderId();

        BigDecimal liquidacaoTotalValor = targetPosition.getMarketValue();
        if (liquidacaoTotalValor.compareTo(BigDecimal.ZERO) <= 0) {
            log.error("❌ [MAPPER RESGATE] Valor de mercado da posição para {} é zero ou negativo (R$ {}). Veto de ordem.",
                    targetPosition.getSymbol(), liquidacaoTotalValor.toPlainString());
            throw new IllegalArgumentException("Valor da posição inválido para liquidação. Veto de ordem.");
        }

        ibkrOrder.orderId((int) nextOrderId);
        ibkrOrder.permId((int) targetPosition.getConId());
        ibkrOrder.account(targetPosition.getAccount());
        ibkrOrder.clientId(emergencyOrder.getClientId().hashCode());
        ibkrOrder.orderRef(emergencyOrder.getClientOrderId());
        ibkrOrder.action(emergencyOrder.getSide().toString());
        ibkrOrder.orderType(DEFAULT_ORDER_TYPE);
        ibkrOrder.tif(DEFAULT_TIF);
        ibkrOrder.cashQty(liquidacaoTotalValor.doubleValue());

        log.warn("🚨 [MAPPER RESGATE] Ordem Resgate ({}) mapeada. CashQty (Valor): R$ {}. Preparada para roteamento SMART no Contract.",
                emergencyOrder.getClientOrderId(), liquidacaoTotalValor.toPlainString());

        return ibkrOrder;
    }

    // =========================================================================
    // 🚀 ADIÇÕES PARA SINERGIA (ENVIO NORMAL EUA / JAPÃO)
    // =========================================================================

    /**
     * 🎌 NOVO: Cria o contrato IBKR com roteamento regional automático.
     * Resolve o envio para TSEJ (Japão) e SMART (EUA).
     */
    public Contract toContract(String symbol) {
        Contract contract = new Contract();

        // Remove sufixo para a IBKR (Ex: 6501.T -> 6501)
        String cleanSymbol = symbol.contains(".") ? symbol.split("\\.")[0] : symbol;

        contract.symbol(cleanSymbol);
        contract.secType(Types.SecType.STK);

        if (symbol.endsWith(".T")) {
            // Configuração obrigatória para Japão
            contract.currency("JPY");
            contract.exchange("TSEJ");
            contract.primaryExch("TSEJ");
            log.info("🎌 [MAPPER] Contrato JAPÃO: {} | Moeda: JPY", symbol);
        } else {
            // Configuração padrão para EUA
            contract.currency("USD");
            contract.exchange("SMART");
            log.info("🇺🇸 [MAPPER] Contrato EUA: {} | Moeda: USD", symbol);
        }
        return contract;
    }

    /**
     * ✅ NOVO: Mapeia ordem normal baseada em QUANTIDADE.
     * Usado para ordens vindas do Orquestrador que não são de emergência.
     */
    public Order mapToIbkrOrder(int orderId, String side, BigDecimal quantity) {
        Order order = new Order();
        order.orderId(orderId);
        order.action(side);
        order.orderType(DEFAULT_ORDER_TYPE);
        order.totalQuantity(com.ib.client.Decimal.get(quantity.doubleValue()));
        order.transmit(true);

        log.info("📦 [MAPPER] Ordem NORMAL construída: ID {} | Qtd {}", orderId, quantity);
        return order;
    }
}