package com.example.homegaibkrponte.factory;

import com.example.homegaibkrponte.connector.IBKRConnector;
import com.example.homegaibkrponte.dto.OrderDTO;
import com.example.homegaibkrponte.model.OrderType;
import com.ib.client.Order;
import com.ib.client.Decimal;
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
        ibkrOrder.totalQuantity(Decimal.get(dto.quantity()));

        // CORREÇÃO TIPAGEM: Usar BigDecimal.ZERO como fallback para lmtPrice
        ibkrOrder.lmtPrice(Optional.ofNullable(dto.price()).orElse(BigDecimal.ZERO).doubleValue());

        // 2. Mapeamento CRÍTICO: Ação e Tipo (AGORA COMPILA)
        ibkrOrder.action(dto.type().getSide().name());

        // ⚠️ CORREÇÃO Alias: Usando o FQN (com.ib.client.OrderType)
        com.ib.client.OrderType ibkrType = determineIbkrOrderType(dto.type());
        ibkrOrder.orderType(ibkrType.name());

        // 3. Mapeamento de Stop Loss/Take Profit (para ordens FILHAS do Bracket)
        if (dto.isStopLoss()) {
            ibkrOrder.orderType(com.ib.client.OrderType.STP.name());
            // CORREÇÃO TIPAGEM: Usar BigDecimal.ZERO como fallback para auxPrice
            ibkrOrder.auxPrice(Optional.ofNullable(dto.stopLossPrice()).orElse(BigDecimal.ZERO).doubleValue());
        } else if (dto.isTakeProfit()) {
            ibkrOrder.orderType(com.ib.client.OrderType.LMT.name());
            // CORREÇÃO TIPAGEM: Usar BigDecimal.ZERO como fallback para lmtPrice (do TP)
            ibkrOrder.lmtPrice(Optional.ofNullable(dto.takeProfitPrice()).orElse(BigDecimal.ZERO).doubleValue());
        }

        // 4. Configurações de Risco/Sessão (Boas Práticas)
        ibkrOrder.tif("GTC");
        ibkrOrder.outsideRth(true);
        ibkrOrder.account(connector.getAccountId()); // Chama o método corrigido!

        return ibkrOrder;
    }

    // ----------------------------------------------------
    // MÉTODOS DE CONVERSÃO INTERNA (SRP)
    // ----------------------------------------------------

    /**
     * Mapeia nosso OrderType (Ponte) para o OrderType nativo da IBKR.
     */
    private com.ib.client.OrderType determineIbkrOrderType(OrderType orderType) {
        return switch (orderType) {
            case BUY_MARKET, SELL_MARKET -> com.ib.client.OrderType.MKT;
            case STOP_LOSS, SELL_STOP_LOSS, BUY_STOP, SELL_STOP -> com.ib.client.OrderType.STP;
            case TAKE_PROFIT, SELL_TAKE_PROFIT, BUY_LIMIT, SELL_LIMIT -> com.ib.client.OrderType.LMT;
            default -> com.ib.client.OrderType.MKT; // Padrão
        };
    }
}