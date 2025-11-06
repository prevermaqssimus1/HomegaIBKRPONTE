package com.example.homegaibkrponte.connector.mapper;

import com.example.homegaibkrponte.model.Order;
import com.example.homegaibkrponte.service.OrderIdManager; // SINERGIA: Para IDs de Ordem
import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.OrderType; // Importando o OrderType do IBKR
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Implementação concreta do IBKRMapper, responsável por converter objetos de domínio (Ordem)
 * para objetos da API IBKR (Contract e com.ib.client.Order).
 * Implementa SRP e respeita o DIP (via interface IBKRMapper).
 */
@Component // ✅ Define esta classe como um Bean do Spring
@Slf4j
@RequiredArgsConstructor
// ✅ Implementa a interface que você definiu
public class IBKRMapperImpl implements IBKRMapper {

    // 💡 SINERGIA: Necessário para obter o próximo ID de ordem válido.
    private final OrderIdManager orderIdManager;

    /**
     * Converte o objeto Order do Principal para o objeto Contract da IBKR.
     */
    @Override
    public Contract toContract(Order domainOrder) {
        Contract contract = new Contract();

        // 1. Definições básicas do Contrato
        contract.symbol(domainOrder.symbol());
        contract.secType("STK"); // Assumindo ações (Stocks)
        contract.exchange("SMART"); // Roteamento inteligente
        contract.currency("USD"); // Moeda americana (Ajuste conforme sua necessidade)

        // 2. Logs Explicativos (para rastrear a conversão)
        log.info("⚙️ [PONTE | MAPPER] Mapeando Contrato para {}. Tipo: {}. Exchange: {}",
                domainOrder.symbol(), contract.secType(), contract.exchange());

        return contract;
    }

    /**
     * Converte o objeto Order do Principal para o objeto Order nativo da IBKR.
     */
    @Override
    public com.ib.client.Order toIBKROrder(Order domainOrder) {
        com.ib.client.Order ibkrOrder = new com.ib.client.Order();

        // 1. Obter o próximo ID Válido (SINERGIA CRÍTICA)
        // Isso garante que cada ordem tenha um ID único antes de ser enviada.
        int orderId = orderIdManager.getNextOrderId();
        ibkrOrder.orderId(orderId);
        ibkrOrder.clientId(orderIdManager.getClientId()); // Usa o ID do cliente da IBKR
        ibkrOrder.account(orderIdManager.getAccountId()); // Usando o AccountId real do Manager (Melhor prática)

        // 2. Quantidade e Ação
        // Ação: Se a quantidade for positiva, é COMPRA. Se for negativa, é VENDA.
        if (domainOrder.quantity().compareTo(BigDecimal.ZERO) > 0) {
            ibkrOrder.action("BUY");
        } else {
            ibkrOrder.action("SELL");
        }

        // Quantidade (Deve ser sempre positiva na API IBKR, o sinal é dado pela AÇÃO)
        ibkrOrder.totalQuantity(Decimal.get(domainOrder.quantity().abs()));

        // 3. Tipo de Ordem, Preço e Parâmetros (Lógica de Mapeamento do Enum)
        mapOrderTypeAndPrices(domainOrder, ibkrOrder);

        // 4. Logs Explicativos (para rastrear a ordem final)
        log.debug("⚙️ [PONTE | MAPPER] Ordem IBKR mapeada: ID: {} | Ação: {} | Tipo: {} | Qtd: {}",
                orderId, ibkrOrder.action(), ibkrOrder.orderType(), ibkrOrder.totalQuantity().value());

        return ibkrOrder;
    }

    /**
     * Lógica complexa de mapeamento de tipos de ordem (Switch/Case).
     */
    private void mapOrderTypeAndPrices(Order domainOrder, com.ib.client.Order ibkrOrder) {

        // Valor do preço (arredondado para duas casas decimais, prática comum em ações)
        // Se o preço for nulo, usamos ZERO ou garantimos que não haja preço para ordens MKT.
        BigDecimal price = Optional.ofNullable(domainOrder.price())
                .orElse(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        switch (domainOrder.type()) {
            case BUY_MARKET:
            case SELL_MARKET:
                ibkrOrder.orderType(com.ib.client.OrderType.MKT.name());
                break;
            case BUY_LIMIT:
            case SELL_LIMIT:
                ibkrOrder.orderType(com.ib.client.OrderType.LMT.name());
                ibkrOrder.lmtPrice(price.doubleValue());
                break;
            case BUY_STOP:
            case SELL_STOP:
                ibkrOrder.orderType(com.ib.client.OrderType.STP.name());
                ibkrOrder.auxPrice(price.doubleValue()); // Preço Stop
                break;
            case BUY_TO_COVER:
                ibkrOrder.orderType(com.ib.client.OrderType.MKT.name());
                ibkrOrder.action("BUY");
                break;
            case STOP_LOSS:
            case SELL_STOP_LOSS:
                ibkrOrder.orderType(com.ib.client.OrderType.STP.name());
                ibkrOrder.auxPrice(domainOrder.stopLossPrice().doubleValue());
                break;
            case TAKE_PROFIT:
            case SELL_TAKE_PROFIT:
                ibkrOrder.orderType(com.ib.client.OrderType.LMT.name());
                ibkrOrder.lmtPrice(domainOrder.takeProfitPrice().doubleValue());
                break;
            default:
                log.error("❌ [PONTE | MAPPER] Tipo de ordem desconhecido ou não suportado: {}", domainOrder.type());
                ibkrOrder.orderType(com.ib.client.OrderType.MKT.name());
        }
    }
}