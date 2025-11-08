package com.example.homegaibkrponte.connector.mapper;

import com.example.homegaibkrponte.model.Order;
// Importação CRÍTICA: Assegura que estamos usando o enum da PONTE (MKT, LMT, STP)
import com.example.homegaibkrponte.model.OrderType;
import com.example.homegaibkrponte.service.OrderIdManager;
import com.ib.client.Contract;
import com.ib.client.Decimal;
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
 * * ✅ NOTA: Esta é a PONTE. O método mapOrderTypeAndPrices foi ajustado para depender
 * unicamente do OrderType da Ponte (MKT, LMT, STP) e não dos tipos compostos do Principal.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class IBKRMapperImpl implements IBKRMapper {

    private final OrderIdManager orderIdManager; // [cite: 34]

    @Override
    public Contract toContract(Order domainOrder) { // [cite: 35]
        Contract contract = new Contract();
        contract.symbol(domainOrder.symbol());
        contract.secType("STK"); // [cite: 37]
        contract.exchange("SMART"); // [cite: 38]
        contract.currency("USD"); // [cite: 39]
        log.info("⚙️ [PONTE | MAPPER] Mapeando Contrato para {}. Tipo: {}. Exchange: {}",
                domainOrder.symbol(), contract.secType(), contract.exchange());
        return contract; // [cite: 40]
    }

    @Override
    public com.ib.client.Order toIBKROrder(Order domainOrder) { // [cite: 41]
        com.ib.client.Order ibkrOrder = new com.ib.client.Order();

        // 1. Obter o próximo ID Válido (SINERGIA CRÍTICA) [cite: 42]
        int orderId = orderIdManager.getNextOrderId(); // [cite: 43]
        ibkrOrder.orderId(orderId);
        ibkrOrder.clientId(orderIdManager.getClientId());
        ibkrOrder.account(orderIdManager.getAccountId()); // [cite: 44]

        // 2. Quantidade e Ação (Resolvido pela quantidade)
        if (domainOrder.quantity().compareTo(BigDecimal.ZERO) > 0) {
            ibkrOrder.action("BUY"); // [cite: 45]
        } else {
            ibkrOrder.action("SELL"); // [cite: 46]
        }

        // Quantidade (Deve ser sempre positiva na API IBKR)
        ibkrOrder.totalQuantity(Decimal.get(domainOrder.quantity().abs())); // [cite: 47]

        // 3. Tipo de Ordem, Preço e Parâmetros
        mapOrderTypeAndPrices(domainOrder, ibkrOrder); // [cite: 48]

        // 4. Logs Explicativos
        log.debug("⚙️ [PONTE | MAPPER] Ordem IBKR mapeada: ID: {} | Ação: {} | Tipo: {} | Qtd: {}",
                orderId, ibkrOrder.action(), ibkrOrder.orderType(), ibkrOrder.totalQuantity().value()); // [cite: 49]

        return ibkrOrder; // [cite: 50]
    }

    /**
     * ✅ AJUSTADO PARA SINERGIA TOTAL: Foca apenas nos tipos de ordem da PONTE (MKT, LMT, STP).
     * A lógica de intenção (BUY/SELL) e tipos compostos foi resolvida no Principal/OrderFactory.
     */
    private void mapOrderTypeAndPrices(Order domainOrder, com.ib.client.Order ibkrOrder) { // [cite: 51]

        // Valor do preço para LMT ou STP (arredondado)
        // Se o preço for nulo, usamos ZERO.
        BigDecimal price = Optional.ofNullable(domainOrder.price()) // [cite: 52]
                .orElse(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        // O switch utiliza o OrderType do DOMÍNIO (que deve ser MKT, LMT, STP, conforme o enum da Ponte)
        switch (domainOrder.type()) {
            case MKT:
                // BUY_MARKET e SELL_MARKET já estão mapeados para MKT e a ação está acima.
                ibkrOrder.orderType(com.ib.client.OrderType.MKT.name()); // [cite: 54]
                // MKT não usa lmtPrice ou auxPrice
                break;

            case LMT:
                // BUY_LIMIT e SELL_LIMIT já estão mapeados para LMT e a ação está acima.
                ibkrOrder.orderType(com.ib.client.OrderType.LMT.name()); // [cite: 55]
                ibkrOrder.lmtPrice(price.doubleValue());
                break;

            case STP:
                // BUY_STOP e SELL_STOP já estão mapeados para STP e a ação está acima.
                ibkrOrder.orderType(com.ib.client.OrderType.STP.name()); // [cite: 56]
                // Assumimos que o campo 'price' do domínio contém o preço Stop para STP simples.
                ibkrOrder.auxPrice(price.doubleValue());
                break;

            // 🛑 REMOVIDOS OS ENUMS COMPOSTOS (Ex: BUY_MARKET, SELL_STOP_LOSS)
            // Eles pertencem à lógica de intenção no Principal.
            // O record Order deve ter OrderType = MKT/LMT/STP.

            default:
                log.error("❌ [PONTE | MAPPER] Tipo de ordem da Ponte desconhecido ou não suportado: {}", domainOrder.type()); // [cite: 60]
                ibkrOrder.orderType(com.ib.client.OrderType.MKT.name()); // [cite: 61]
        }
    }
}