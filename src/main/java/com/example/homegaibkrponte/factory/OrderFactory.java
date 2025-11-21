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

        // 1. Mapeamento Básico de Fields
        ibkrOrder.orderId(Integer.parseInt(ibkrClientOrderId));
        // ✅ Boa Prática: Usar Decimal.get() para maior precisão
        ibkrOrder.totalQuantity(Decimal.get(dto.quantity()));

        // 2. Mapeamento CRÍTICO: Ação (BUY/SELL)
        // Obtém a string da Ação (BUY ou SELL) do OrderTypeEnum do Principal (Fonte da Ação)
        String actionString = dto.type().getSide();
        ibkrOrder.action(actionString); // Define a Ação TWS (Ex: "BUY" ou "SELL")

        // 3. Mapeamento CRÍTICO do Tipo de Ordem da PONTE (MKT, LMT, STP)
        com.ib.client.OrderType ibkrType = determineIbkrOrderType(dto.type());
        ibkrOrder.orderType(ibkrType.name());

        // 4. Definição de Preços (Com base no tipo da PONTE)

        if (ibkrType == OrderType.MKT) {
            // Para MKT, o preço e o preço auxiliar DEVEM ser zero.
            ibkrOrder.lmtPrice(PRICE_ZERO);
            ibkrOrder.auxPrice(PRICE_ZERO);

        } else if (ibkrType == OrderType.LMT) {
            // Ordem LMT pura, TAKE_PROFIT, ou Resgate Inteligente
            double limitPrice = Optional.ofNullable(dto.takeProfitPrice()) // Prioriza TP se for o caso
                    .or(() -> Optional.ofNullable(dto.limitPrice())) // Prioriza limitPrice (Resgate Inteligente)
                    .or(() -> Optional.ofNullable(dto.price())) // Fallback para preço genérico
                    .filter(p -> p.compareTo(BigDecimal.ZERO) > 0)
                    .orElseThrow(() -> new IllegalStateException("Ordem LMT requer um preço limite válido."))
                    .doubleValue();
            ibkrOrder.lmtPrice(limitPrice);
        }

        // 🚨 Mapeamento de STOP (STP) - Reforçando a lógica:
        // Cobre BUY_STOP, SELL_STOP, SELL_STOP_LOSS, BUY_STOP_LOSS
        else if (ibkrType == OrderType.STP) {
            // O preço de Stop (auxPrice) pode vir de stopLossPrice (para SL/TP) ou do price principal (para STP simples)
            double stopPrice = Optional.ofNullable(dto.stopLossPrice()) // Prioriza stopLossPrice se for SL
                    .or(() -> Optional.ofNullable(dto.price())) // Fallback para preço (para BUY_STOP/SELL_STOP)
                    .filter(p -> p.compareTo(BigDecimal.ZERO) > 0)
                    .orElseThrow(() -> new IllegalStateException("Ordem STOP requer um preço de stop válido."))
                    .doubleValue();
            ibkrOrder.auxPrice(stopPrice);
            ibkrOrder.lmtPrice(PRICE_ZERO); // STP não tem preço limite, apenas o gatilho (auxPrice)
        }

        // 5. Configurações de Risco/Sessão (CRÍTICO: Conta)
        ibkrOrder.tif("GTC");
        ibkrOrder.outsideRth(true);
        ibkrOrder.account(connector.getAccountId()); // Garante o accountId correto

        return ibkrOrder;
    }

    /**
     * ✅ SINERGIA TOTAL: Resolve o conflito de tipagem e mapeia o Enum de INTENÇÃO (Principal)
     * para o tipo IBKR (Ponte/TWS).
     * @param orderType O enum de tipo de ordem da aplicação Principal.
     */
    private com.ib.client.OrderType determineIbkrOrderType(OrderTypeEnum orderType) {

        // Mapeia os tipos de domínio para os tipos nativos da IBKR.
        return switch (orderType) {
            // Mercado
            case BUY_MARKET, SELL_MARKET, MKT -> com.ib.client.OrderType.MKT;

            // Limitada (Inclui ordens de lucro, que são LMT)
            case BUY_LIMIT, SELL_LIMIT, SELL_TAKE_PROFIT, BUY_TAKE_PROFIT, LMT -> com.ib.client.OrderType.LMT;

            // Stop (Inclui ordens de proteção, que são STP)
            case BUY_STOP, SELL_STOP, SELL_STOP_LOSS, BUY_STOP_LOSS -> com.ib.client.OrderType.STP;

            // Tratamento especial para BUY_TO_COVER
            case BUY_TO_COVER -> com.ib.client.OrderType.MKT; // Usado para execução imediata

            default -> {
                // Logar o erro e retornar o tipo MKT como fallback seguro
                System.err.println("Tipo de Ordem do Principal desconhecido: " + orderType + ". Usando MKT como fallback.");
                yield com.ib.client.OrderType.MKT;
            }
        };
    }
}