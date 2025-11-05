package com.example.homegaibkrponte.factory;

import com.example.homegaibkrponte.connector.IBKRConnector;
import com.example.homegaibkrponte.dto.OrderDTO;
import com.ib.client.Order;
import com.ib.client.Decimal;
import com.ib.client.OrderType;
import com.ib.client.Types;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Padrão Factory (SRP): Responsável por converter o nosso OrderDTO (Core)
 * para o objeto Order nativo da IBKR, tratando todas as diferenças de tipo e roteamento.
 */
@Component
public class OrderFactory {

    private final IBKRConnector connector;
    private static final double PRICE_ZERO = 0.0; // Valor que o TWS espera para Market Orders

    public OrderFactory(IBKRConnector connector) {
        this.connector = connector;
    }

    /**
     * Cria um objeto Order nativo da IBKR a partir do nosso OrderDTO.
     */
    public Order create(OrderDTO dto, String ibkrClientOrderId) {

        Order ibkrOrder = new Order();

        // 1. Mapeamento Básico de Fields
        ibkrOrder.orderId(Integer.parseInt(ibkrClientOrderId));
        // Note: A conversão para Decimal.get(BigDecimal) é usada para quantidades maiores que Double.MAX_VALUE
        ibkrOrder.totalQuantity(Decimal.get(dto.quantity()));

        // 2. Mapeamento CRÍTICO: Ação e Tipo
        ibkrOrder.action(dto.type().getSide().name());

        // 3. Mapeamento CRÍTICO do Tipo de Ordem e Preços (Onde o erro INACTIVE ocorre)
        com.ib.client.OrderType ibkrType = determineIbkrOrderType(dto.type());
        ibkrOrder.orderType(ibkrType.name());

        if (ibkrType == OrderType.MKT) {
            // ✅ CORREÇÃO CRÍTICA: Para MKT, o preço e o preço auxiliar DEVEM ser zero.
            ibkrOrder.lmtPrice(PRICE_ZERO);
            ibkrOrder.auxPrice(PRICE_ZERO);
        } else if (ibkrType == OrderType.LMT) {
            // Para ordens LIMIT, usa o preço do DTO (que o robô calculou).
            ibkrOrder.lmtPrice(Optional.ofNullable(dto.price()).orElse(BigDecimal.ZERO).doubleValue());
        }

        // 4. Mapeamento de Stop Loss/Take Profit (para ordens FILHAS do Bracket)
        // Note: Esta lógica é para ordens de Resgate/Saída (SELL MKT), e não para ordens filhas de Bracket.
        if (dto.isStopLoss()) {
            ibkrOrder.orderType(com.ib.client.OrderType.STP.name());
            ibkrOrder.auxPrice(Optional.ofNullable(dto.stopLossPrice()).orElse(BigDecimal.ZERO).doubleValue());
        } else if (dto.isTakeProfit()) {
            ibkrOrder.orderType(com.ib.client.OrderType.LMT.name());
            ibkrOrder.lmtPrice(Optional.ofNullable(dto.takeProfitPrice()).orElse(BigDecimal.ZERO).doubleValue());
        }

        // 5. Configurações de Risco/Sessão (CRÍTICO: Conta)
        ibkrOrder.tif("GTC");
        ibkrOrder.outsideRth(true);
        // ✅ CORREÇÃO: Garante que o accountId correto seja enviado para a ordem.
        ibkrOrder.account(connector.getAccountId());

        return ibkrOrder;
    }

    private com.ib.client.OrderType determineIbkrOrderType(com.example.homegaibkrponte.model.OrderType orderType) {
        return switch (orderType) {
            case BUY_MARKET, SELL_MARKET -> com.ib.client.OrderType.MKT;
            case STOP_LOSS, SELL_STOP_LOSS, BUY_STOP, SELL_STOP -> com.ib.client.OrderType.STP;
            case TAKE_PROFIT, SELL_TAKE_PROFIT, BUY_LIMIT, SELL_LIMIT -> com.ib.client.OrderType.LMT;
            default -> com.ib.client.OrderType.MKT;
        };
    }
}