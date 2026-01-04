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
    /**
     * ✅ AJUSTE DEFINITIVO: Criação de Ordem IBKR a partir do OrderDTO (Record).
     * Resolve a falta do campo 'side' e a transição para 'type' como String.
     */
    public Order create(OrderDTO dto, String ibkrClientOrderId) {
        com.ib.client.Order ibkrOrder = new com.ib.client.Order();

        // 1. IDENTIFICAÇÃO E QUANTIDADE
        ibkrOrder.orderId(Integer.parseInt(ibkrClientOrderId));
        ibkrOrder.totalQuantity(com.ib.client.Decimal.get(dto.quantity()));

        // 2. RECUPERAÇÃO DO ENUM (Para extrair Side e Tipo IBKR)
        OrderTypeEnum typeEnum = dto.getTypeAsEnum();
        if (typeEnum == null) {
            throw new IllegalStateException("Tipo de ordem inválido ou não reconhecido: " + dto.type());
        }

        // ✅ SINERGIA: O Side (BUY/SELL) é extraído do Enum mapeado internamente
        String actionString = typeEnum.getSide();
        ibkrOrder.action(actionString);

        // 3. DETERMINAÇÃO DO TIPO DE ORDEM (MKT, LMT, STP)
        com.ib.client.OrderType ibkrType = determineIbkrOrderType(typeEnum);
        ibkrOrder.orderType(ibkrType);

        // 4. LÓGICA DE PREÇOS (Resiliente a campos nulos)
        if (ibkrType == com.ib.client.OrderType.MKT) {
            ibkrOrder.lmtPrice(0);
            ibkrOrder.auxPrice(0);
            // Sinaliza intenção de compensação direta para facilitar aceitação em margem baixa
            ibkrOrder.clearingIntent("IB");

        } else if (ibkrType == com.ib.client.OrderType.LMT) {
            double limitPrice = Optional.ofNullable(dto.limitPrice()) // Prioridade ao campo específico
                    .or(() -> Optional.ofNullable(dto.takeProfitPrice()))
                    .or(() -> Optional.ofNullable(dto.price()))
                    .filter(p -> p.compareTo(BigDecimal.ZERO) > 0)
                    .orElseThrow(() -> new IllegalStateException("Ordem LMT requer preço válido."))
                    .doubleValue();
            ibkrOrder.lmtPrice(limitPrice);
        }
        else if (ibkrType == com.ib.client.OrderType.STP) {
            double stopPrice = Optional.ofNullable(dto.stopLossPrice())
                    .or(() -> Optional.ofNullable(dto.price()))
                    .filter(p -> p.compareTo(BigDecimal.ZERO) > 0)
                    .orElseThrow(() -> new IllegalStateException("Ordem STOP requer preço auxiliar."))
                    .doubleValue();
            ibkrOrder.auxPrice(stopPrice);
            ibkrOrder.lmtPrice(0);
        }

        // 5. CONFIGURAÇÕES PADRÃO E CONTA
        ibkrOrder.tif("GTC");
        ibkrOrder.outsideRth(true);
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