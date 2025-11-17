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

/**
 * Implementação concreta do IBKRMapper, responsável por converter objetos de domínio (Ordem)
 * para objetos da API IBKR (Contract e com.ib.client.Order).
 * Implementa SRP e respeita o DIP (via interface IBKRMapper).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class IBKRMapperImpl implements IBKRMapper {

    private final OrderIdManager orderIdManager;

    @Override
    public Contract toContract(Order domainOrder) {
        Contract contract = new Contract();
        contract.symbol(domainOrder.symbol());
        contract.secType("STK");
        contract.exchange("SMART");
        contract.currency("USD");
        log.info("⚙️ [PONTE | MAPPER] Mapeando Contrato para {}. Tipo: {}. Exchange: {}",
                domainOrder.symbol(), contract.secType(), contract.exchange());
        return contract;
    }

    @Override
    public com.ib.client.Order toIBKROrder(Order domainOrder) {
        com.ib.client.Order ibkrOrder = new com.ib.client.Order();

        // 1. Obter o próximo ID Válido (SINERGIA CRÍTICA)
        int orderId = orderIdManager.getNextOrderId();
        ibkrOrder.orderId(orderId);
        ibkrOrder.clientId(orderIdManager.getClientId());
        ibkrOrder.account(orderIdManager.getAccountId());

        // 2. Quantidade e Ação (Resolvido pela quantidade)
        if (domainOrder.quantity().compareTo(BigDecimal.ZERO) > 0) {
            ibkrOrder.action("BUY");
        } else {
            ibkrOrder.action("SELL");
        }

        // Quantidade (Deve ser sempre positiva na API IBKR)
        ibkrOrder.totalQuantity(Decimal.get(domainOrder.quantity().abs()));

        // 3. Tipo de Ordem, Preço e Parâmetros
        mapOrderTypeAndPrices(domainOrder, ibkrOrder);

        // 4. Logs Explicativos
        log.debug("⚙️ [PONTE | MAPPER] Ordem IBKR mapeada: ID: {} | Ação: {} | Tipo: {} | Qtd: {}",
                orderId, ibkrOrder.action(), ibkrOrder.orderType(), ibkrOrder.totalQuantity().value());

        return ibkrOrder;
    }

    // --- MÉTODOS DE ADAPTAÇÃO PARA WHAT-IF (NOVOS) ---

    @Override
    public Contract toContract(String symbol) {
        Contract contract = new Contract();
        contract.symbol(symbol);
        contract.secType("STK");
        contract.exchange("SMART");
        contract.currency("USD");
        log.debug("⚙️ [PONTE | MAPPER] Mapeando Contrato What-If para {}.", symbol);
        return contract;
    }

    @Override
    public com.ib.client.Order toWhatIfOrder(int orderId, String side, int quantity) {
        com.ib.client.Order ibkrOrder = new com.ib.client.Order();

        ibkrOrder.orderId(orderId);
        ibkrOrder.action(side);

        // Uso de Long para criar Decimal (resolve erro de construtor)
        ibkrOrder.totalQuantity(Decimal.get(Long.valueOf(quantity)));

        ibkrOrder.orderType(com.ib.client.OrderType.MKT.name());

        // Flags CRÍTICAS para What-If (Eliminando o Bypass de R$ 0.01)
        ibkrOrder.whatIf(true);
        ibkrOrder.transmit(false);

        // ✅ LOG DE CONFIRMAÇÃO 1: Ordem What-If Criada
        log.info("📢 [MAPPER | What-If] Ordem de Simulação Criada. ID: {} | Qtd: {} | WhatIf: TRUE.",
                orderId, quantity);

        return ibkrOrder;
    }

    @Override
    public OrderStateDTO toOrderStateDTO(OrderState ibkrOrderState) {
        OrderStateDTO dto = new OrderStateDTO();

        // Mapeamento dos campos de margem (Strings nativas IBKR)
        dto.setInitMarginChange(ibkrOrderState.initMarginChange());
        dto.setMaintMarginChange(ibkrOrderState.maintMarginChange());

        // Mapeamento do campo de liquidez pós-trade mais próximo
        dto.setEquityWithLoanAfter(ibkrOrderState.equityWithLoanAfter());

        // Mapeamento de status
        dto.setStatus(ibkrOrderState.getStatus());

        // ✅ LOG DE CONFIRMAÇÃO 2: DTO de Estado Mapeado
        log.info("📢 [MAPPER | State] DTO de Estado Mapeado. Status: {} | InitChange (Raw): {}",
                ibkrOrderState.getStatus(), ibkrOrderState.initMarginChange());

        return dto;
    }

    @Override
    public BigDecimal parseMarginValue(String marginValue) {
        if (marginValue == null || marginValue.isEmpty() || marginValue.equalsIgnoreCase("N/A")) {
            return BigDecimal.ZERO;
        }
        try {
            // Remove caracteres de moeda e de grupo de milhares
            String cleanedValue = marginValue.replaceAll("[^0-9\\.\\-]", "");

            // Verifica se é o marcador de valor não definido (UNSET)
            if (cleanedValue.contains("E308") || cleanedValue.contains("E+308")) {
                log.warn("Valor de margem recebido como Double.MAX_VALUE (UNSET). Interpretando como zero.");
                return BigDecimal.ZERO;
            }

            BigDecimal result = new BigDecimal(cleanedValue);

            // ✅ LOG DE CONFIRMAÇÃO 3: Valor de Margem Processado
            log.info("📢 [MAPPER | Parse] Valor de Margem (Raw: '{}') convertido para BigDecimal: R$ {}.",
                    marginValue, result);

            return result;
        } catch (NumberFormatException e) {
            log.error("❌ Falha ao converter valor de margem '{}' para BigDecimal. Retornando ZERO. Rastreando.", marginValue, e);
            return BigDecimal.ZERO;
        }
    }

    // --- MÉTODOS AUXILIARES ORIGINAIS (MANTIDOS) ---

    private void mapOrderTypeAndPrices(Order domainOrder, com.ib.client.Order ibkrOrder) {

        BigDecimal price = Optional.ofNullable(domainOrder.price())
                .orElse(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        switch (domainOrder.type()) {
            case MKT:
                ibkrOrder.orderType(com.ib.client.OrderType.MKT.name());
                break;

            case LMT:
                ibkrOrder.orderType(com.ib.client.OrderType.LMT.name());
                ibkrOrder.lmtPrice(price.doubleValue());
                break;

            case STP:
                ibkrOrder.orderType(com.ib.client.OrderType.STP.name());
                ibkrOrder.auxPrice(price.doubleValue());
                break;

            default:
                log.error("❌ [PONTE | MAPPER] Tipo de ordem da Ponte desconhecido ou não suportado: {}", domainOrder.type());
                ibkrOrder.orderType(com.ib.client.OrderType.MKT.name());
        }
    }
}