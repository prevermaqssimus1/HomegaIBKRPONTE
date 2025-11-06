package com.example.homegaibkrponte.factory;

import com.example.homegaibkrponte.connector.IBKRConnector;
import com.example.homegaibkrponte.dto.OrderDTO;
import com.ib.client.Order;
import com.ib.client.Decimal;
import com.ib.client.OrderType; // TWS API OrderType
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Padrão Factory (SRP): Responsável por converter o nosso OrderDTO (Core)
 * para o objeto Order nativo da IBKR, tratando todas as diferenças de tipo e roteamento.
 *
 * Esta é uma classe da **PONTE**.
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
        ibkrOrder.totalQuantity(Decimal.get(dto.quantity()));

        // 2. Mapeamento CRÍTICO: Ação e Tipo

        // ✅ CORREÇÃO CRÍTICA (SINERGIA): Obtém a string da Ação (BUY ou SELL)
        // O método dto.type().getSide() no OrderTypeEnum da Ponte é a fonte da ação.
        // Se a ordem for BUY_TO_COVER (Resgate Short), o getSide() deve retornar "BUY".
        String actionString = dto.type().getSide();
        ibkrOrder.action(actionString); // Define a Ação TWS (Ex: "BUY" ou "SELL")

        // 3. Mapeamento CRÍTICO do Tipo de Ordem e Preços
        com.ib.client.OrderType ibkrType = determineIbkrOrderType(dto.type());
        ibkrOrder.orderType(ibkrType.name());

        if (ibkrType == OrderType.MKT) {
            // Para MKT, o preço e o preço auxiliar DEVEM ser zero.
            ibkrOrder.lmtPrice(PRICE_ZERO);
            ibkrOrder.auxPrice(PRICE_ZERO);
        } else if (ibkrType == OrderType.LMT) {
            // 🚨 AJUSTE PARA RESGATE INTELIGENTE: Prioriza 'limitPrice' (que carrega o preço agressivo do Resgate)
            double limitPrice = Optional.ofNullable(dto.limitPrice())
                    .or(() -> Optional.ofNullable(dto.price()))
                    .filter(p -> p.compareTo(BigDecimal.ZERO) > 0)
                    .orElseThrow(() -> new IllegalStateException("Ordem LMT requer um preço limite válido."))
                    .doubleValue();
            ibkrOrder.lmtPrice(limitPrice);
        }

        // 4. Mapeamento de Stop Loss/Take Profit (Saídas/Proteções)

        // 🚨 AJUSTE CRÍTICO: STOP LOSS (STP) - VETO SE NÃO HOUVER PREÇO
        if (dto.isStopLoss()) {
            ibkrOrder.orderType(com.ib.client.OrderType.STP.name());
            double stopPrice = Optional.ofNullable(dto.stopLossPrice())
                    .filter(p -> p.compareTo(BigDecimal.ZERO) > 0)
                    .orElseThrow(() -> new IllegalStateException("Ordem STOP_LOSS requer um stopLossPrice válido."))
                    .doubleValue();
            ibkrOrder.auxPrice(stopPrice);
            ibkrOrder.lmtPrice(PRICE_ZERO);
        }

        // 🚨 AJUSTE CRÍTICO: TAKE PROFIT (LMT) - VETO SE NÃO HOUVER PREÇO
        else if (dto.isTakeProfit()) {
            ibkrOrder.orderType(com.ib.client.OrderType.LMT.name());
            double limitPrice = Optional.ofNullable(dto.takeProfitPrice())
                    .filter(p -> p.compareTo(BigDecimal.ZERO) > 0)
                    .orElseThrow(() -> new IllegalStateException("Ordem TAKE_PROFIT requer um takeProfitPrice válido."))
                    .doubleValue();
            ibkrOrder.lmtPrice(limitPrice);
        }

        // 5. Configurações de Risco/Sessão (CRÍTICO: Conta)
        ibkrOrder.tif("GTC");
        ibkrOrder.outsideRth(true);
        // ✅ GARANTIA: Garante que o accountId correto seja enviado para a ordem.
        ibkrOrder.account(connector.getAccountId());

        return ibkrOrder;
    }

    /**
     * Resolve o conflito de tipagem e mapeia o Enum interno para o tipo IBKR (TWS).
     * @param orderType O enum de tipo de ordem da aplicação Principal.
     */
    private com.ib.client.OrderType determineIbkrOrderType(com.example.homegaibkrponte.model.OrderTypeEnum orderType) {
        // Mapeia os tipos de domínio para os tipos nativos da IBKR.
        return switch (orderType) {
            case BUY_MARKET, SELL_MARKET -> com.ib.client.OrderType.MKT;
            case MKT -> com.ib.client.OrderType.MKT;
            case LMT -> com.ib.client.OrderType.LMT;
            case STOP_LOSS, SELL_STOP_LOSS, BUY_STOP, SELL_STOP -> com.ib.client.OrderType.STP;
            case TAKE_PROFIT, SELL_TAKE_PROFIT, BUY_LIMIT, SELL_LIMIT -> com.ib.client.OrderType.LMT;

            default -> com.ib.client.OrderType.MKT;
        };
    }
}