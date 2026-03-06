package com.example.homegaibkrponte.connector.mapper;

import com.example.homegaibkrponte.model.Order;
import com.example.homegaibkrponte.model.OrderStateDTO;
import com.example.homegaibkrponte.service.OrderIdManager;
import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.OrderState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

@Component
@Slf4j
@RequiredArgsConstructor
public class IBKRMapperImpl implements IBKRMapper {

    private final OrderIdManager orderIdManager;

    @Override
    public Contract toContract(Order domainOrder) {
        return toContract(domainOrder.symbol());
    }

    /**
     * ✅ AJUSTE MUNDIAL: Mapeamento de Contrato para evitar Erro 200.
     * Segue as diretrizes da IBKR para roteamento internacional.
     */
    @Override
    public Contract toContract(String symbol) {
        Contract contract = new Contract();

        // 1. Identificação do sufixo (Ex: 6501.T, 7203.T, SAP.DE)
        String cleanSymbol = symbol.contains(".") ? symbol.split("\\.")[0] : symbol;
        contract.symbol(cleanSymbol);
        contract.secType("STK");

        // 🎌 ROTEAMENTO JAPÃO (.T)
        if (symbol.endsWith(".T")) {
            contract.currency("JPY");
            contract.exchange("TSE");        // Ajustado de TSEJ para TSE (Padrão mais aceito)
            contract.primaryExch("TSE");     // Necessário para desambiguação
            log.warn("🎌 [INFRA-IBKR] Roteamento Japão: {} -> TSE/JPY", cleanSymbol);
        }
        // 🇪🇺 ROTEAMENTO EUROPA (Ex: .DE - Alemanha, .PA - França)
        else if (symbol.contains(".")) {
            String suffix = symbol.substring(symbol.lastIndexOf(".") + 1).toUpperCase();
            contract.currency("EUR");

            if (suffix.equals("DE")) {
                contract.exchange("IBIS");   // Xetra/Alemanha
                contract.primaryExch("IBIS");
            } else {
                contract.exchange("SMART");
            }
            log.warn("🇪🇺 [INFRA-IBKR] Roteamento Europa: {} -> SMART/EUR", cleanSymbol);
        }
        // 🇺🇸 ROTEAMENTO EUA (Padrão)
        else {
            contract.currency("USD");
            contract.exchange("SMART");
            log.info("🇺🇸 [INFRA-IBKR] Roteamento EUA: {} -> SMART/USD", symbol);
        }

        return contract;
    }

    @Override
    public com.ib.client.Order toIBKROrder(Order domainOrder) {
        com.ib.client.Order ibkrOrder = new com.ib.client.Order();
        int orderId = orderIdManager.getNextOrderId();
        ibkrOrder.orderId(orderId);
        ibkrOrder.clientId(orderIdManager.getClientId());
        ibkrOrder.account(orderIdManager.getAccountId());

        if (domainOrder.quantity().compareTo(BigDecimal.ZERO) > 0) {
            ibkrOrder.action("BUY");
        } else {
            ibkrOrder.action("SELL");
        }

        ibkrOrder.totalQuantity(Decimal.get(domainOrder.quantity().abs()));
        mapOrderTypeAndPrices(domainOrder, ibkrOrder);

        return ibkrOrder;
    }

    @Override
    public com.ib.client.Order toWhatIfOrder(int orderId, String side, int quantity) {
        com.ib.client.Order ibkrOrder = new com.ib.client.Order();
        ibkrOrder.orderId(orderId);
        ibkrOrder.action(side);
        ibkrOrder.totalQuantity(Decimal.get(Long.valueOf(quantity)));
        ibkrOrder.orderType(com.ib.client.OrderType.MKT.name());
        ibkrOrder.whatIf(true);
        ibkrOrder.transmit(false);
        return ibkrOrder;
    }

    @Override
    public OrderStateDTO toOrderStateDTO(OrderState ibkrOrderState) {
        OrderStateDTO dto = new OrderStateDTO();
        dto.setInitMarginChange(ibkrOrderState.initMarginChange());
        dto.setMaintMarginChange(ibkrOrderState.maintMarginChange());
        dto.setEquityWithLoanAfter(ibkrOrderState.equityWithLoanAfter());
        dto.setStatus(ibkrOrderState.getStatus());
        return dto;
    }

    @Override
    public BigDecimal parseMarginValue(String marginValue) {
        if (marginValue == null || marginValue.isEmpty() || marginValue.equalsIgnoreCase("N/A")) {
            return BigDecimal.ZERO;
        }
        try {
            String cleanedValue = marginValue.replaceAll("[^0-9\\.\\-]", "");
            if (cleanedValue.contains("E308") || cleanedValue.contains("E+308")) return BigDecimal.ZERO;
            return new BigDecimal(cleanedValue);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private void mapOrderTypeAndPrices(Order domainOrder, com.ib.client.Order ibkrOrder) {
        BigDecimal price = Optional.ofNullable(domainOrder.price())
                .orElse(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        switch (domainOrder.type()) {
            case MKT -> ibkrOrder.orderType(com.ib.client.OrderType.MKT.name());
            case LMT -> {
                ibkrOrder.orderType(com.ib.client.OrderType.LMT.name());
                ibkrOrder.lmtPrice(price.doubleValue());
            }
            case STP -> {
                ibkrOrder.orderType(com.ib.client.OrderType.STP.name());
                ibkrOrder.auxPrice(price.doubleValue());
            }
            default -> ibkrOrder.orderType(com.ib.client.OrderType.MKT.name());
        }
    }
}