package com.example.homegaibkrponte.factory;

import com.example.homegaibkrponte.connector.IBKRConnector;
import com.example.homegaibkrponte.dto.OrderDTO;
import com.ib.client.Order;
import com.ib.client.Decimal;
import com.ib.client.OrderType; // TWS API OrderType
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import com.example.homegaibkrponte.model.OrderTypeEnum; // OrderTypeEnum do Principal (Intenção)

import java.math.BigDecimal;
import java.util.Optional;

// 🛑 CORREÇÃO CRÍTICA: Removido o import estático do DomainOrderType, que não existe mais
// import static com.example.homegaibkrponte.model.DomainOrderType.*;

/**
 * Padrão Factory (SRP): Responsável por converter o nosso OrderDTO (Core/Principal)
 * para o objeto Order nativo da IBKR, tratando todas as diferenças de tipo e roteamento.
 *
 * Esta é uma classe da **PONTE**.
 */
@Component
@RequiredArgsConstructor
public class OrderFactory {

    private final IBKRConnector connector;
    private static final double PRICE_ZERO = 0.0; // Valor que o TWS espera para Market Orders

    /**
     * Cria um objeto Order nativo da IBKR a partir do nosso OrderDTO.
     */
    public Order create(OrderDTO dto, String ibkrClientOrderId) {
        Order ibkrOrder = new Order();

        ibkrOrder.orderId(Integer.parseInt(ibkrClientOrderId));
        ibkrOrder.totalQuantity(Decimal.get(dto.quantity()));

        String actionString = dto.type().getSide();
        ibkrOrder.action(actionString);

        com.ib.client.OrderType ibkrType = determineIbkrOrderType(dto.type());
        ibkrOrder.orderType(ibkrType.name());

        if (ibkrType == OrderType.MKT) {
            ibkrOrder.lmtPrice(PRICE_ZERO);
            ibkrOrder.auxPrice(PRICE_ZERO);

            // ✅ AJUSTE CRÍTICO: Sinaliza intenção de compensação direta para facilitar
            // aceitação de ordens de fechamento em cenários de margem baixa.
            ibkrOrder.clearingIntent("IB");

        } else if (ibkrType == OrderType.LMT) {
            double limitPrice = Optional.ofNullable(dto.takeProfitPrice())
                    .or(() -> Optional.ofNullable(dto.limitPrice()))
                    .or(() -> Optional.ofNullable(dto.price()))
                    .filter(p -> p.compareTo(BigDecimal.ZERO) > 0)
                    .orElseThrow(() -> new IllegalStateException("Ordem LMT requer preço."))
                    .doubleValue();
            ibkrOrder.lmtPrice(limitPrice);
        }
        else if (ibkrType == OrderType.STP) {
            double stopPrice = Optional.ofNullable(dto.stopLossPrice())
                    .or(() -> Optional.ofNullable(dto.price()))
                    .filter(p -> p.compareTo(BigDecimal.ZERO) > 0)
                    .orElseThrow(() -> new IllegalStateException("Ordem STOP requer preço."))
                    .doubleValue();
            ibkrOrder.auxPrice(stopPrice);
            ibkrOrder.lmtPrice(PRICE_ZERO);
        }

        ibkrOrder.tif("GTC");
        ibkrOrder.outsideRth(true);

        // Garante que a conta correta está sendo usada no envio
        ibkrOrder.account(connector.getAccountId());

        return ibkrOrder;
    }

    /**
     * ✅ SINERGIA TOTAL: Resolve o conflito de tipagem e mapeia o Enum de INTENÇÃO (Principal)
     * para o tipo IBKR (Ponte/TWS).
     * @param orderType O enum de tipo de ordem da aplicação Principal.
     */
    private com.ib.client.OrderType determineIbkrOrderType(OrderTypeEnum orderType) {
        return switch (orderType) {
            case BUY_MARKET, SELL_MARKET, MKT -> com.ib.client.OrderType.MKT;
            case BUY_LIMIT, SELL_LIMIT, SELL_TAKE_PROFIT, BUY_TAKE_PROFIT, LMT -> com.ib.client.OrderType.LMT;
            case BUY_STOP, SELL_STOP, SELL_STOP_LOSS, BUY_STOP_LOSS -> com.ib.client.OrderType.STP;
            case BUY_TO_COVER -> com.ib.client.OrderType.MKT;
            default -> com.ib.client.OrderType.MKT;
        };
    }
}