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
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class IBKRMapperImpl implements IBKRMapper {

    private final OrderIdManager orderIdManager;

    @Override
    public Contract toContract(String symbol) {
        Contract contract = new Contract();
        String cleanSymbol = symbol.contains(".") ? symbol.split("\\.")[0] : symbol;
        contract.symbol(cleanSymbol);
        contract.secType("STK");
        contract.currency("USD");
        contract.exchange("SMART");
        return contract;
    }

    @Override
    public Contract toContract(Order domainOrder) {
        return toContract(domainOrder.symbol());
    }

    @Override
    public com.ib.client.Order toIBKROrder(Order domainOrder) {
        com.ib.client.Order ibkrOrder = new com.ib.client.Order();

        // ⚠️ CORREÇÃO CRÍTICA: antes usava domainOrder.quantity().signum() > 0
        // para inferir BUY/SELL — mas o modelo Order já tem um campo `side`
        // explícito e confiável (com isCompra()/isVenda() já tratando
        // BUY_TO_COVER como compra e SELL_SHORT como venda). Inferir pelo
        // sinal da quantidade é redundante e perigoso: se quantity() vier
        // sempre como magnitude positiva (dependendo de como o Principal
        // normalizou antes de enviar), essa inferência SEMPRE resultaria em
        // "BUY", mesmo para ordens de venda/short — o que faria TODA ordem
        // que passasse por aqui (incluindo bracket orders inteiros) sair com
        // o lado físico errado.
        // Configurações Base
        ibkrOrder.action(domainOrder.isCompra() ? "BUY" : "SELL");
        ibkrOrder.totalQuantity(Decimal.get(domainOrder.quantity().abs()));
        ibkrOrder.account(orderIdManager.getAccountId());

        // 🎯 AJUSTE: Usando .price() que é o campo real do seu domainOrder
        if (domainOrder.type() != null) {
            ibkrOrder.orderType(domainOrder.type().name());
            if ("LMT".equals(domainOrder.type().name()) && domainOrder.price() != null) {
                ibkrOrder.lmtPrice(domainOrder.price().doubleValue());
            }
        } else {
            ibkrOrder.orderType("MKT");
        }

        return ibkrOrder;
    }

    @Override
    public List<com.ib.client.Order> toBracketOrder(Order domainOrder) {
        List<com.ib.client.Order> bracket = new ArrayList<>();

        // 1. ORDEM PAI (Entrada)
        com.ib.client.Order parent = toIBKROrder(domainOrder);
        parent.orderId(orderIdManager.getNextOrderId());
        parent.transmit(false);
        bracket.add(parent);

        // ⚠️ CORREÇÃO: antes derivava exitAction do resultado de
        // parent.getAction() (que dependia do toIBKROrder já estar certo).
        // Agora deriva diretamente de domainOrder.isCompra(), removendo
        // qualquer dependência frágil entre os dois métodos — a ação de
        // saída (stop loss/take profit) é sempre o oposto físico da entrada.
        String exitAction = domainOrder.isCompra() ? "SELL" : "BUY";

        // 2. FILHA: STOP LOSS (Contingência)
        if (domainOrder.stopLossPrice() != null && domainOrder.stopLossPrice().signum() > 0) {
            com.ib.client.Order stopLoss = new com.ib.client.Order();
            stopLoss.orderId(orderIdManager.getNextOrderId());
            stopLoss.parentId(parent.orderId());
            stopLoss.action(exitAction);
            stopLoss.orderType("STP");
            stopLoss.auxPrice(domainOrder.stopLossPrice().doubleValue());
            stopLoss.totalQuantity(parent.totalQuantity());
            stopLoss.transmit(false);
            bracket.add(stopLoss);
        }

        // 3. FILHA: TAKE PROFIT
        if (domainOrder.takeProfitPrice() != null && domainOrder.takeProfitPrice().signum() > 0) {
            com.ib.client.Order takeProfit = new com.ib.client.Order();
            takeProfit.orderId(orderIdManager.getNextOrderId());
            takeProfit.parentId(parent.orderId());
            takeProfit.action(exitAction);
            takeProfit.orderType("LMT");
            // 🎯 AJUSTE: Aqui usamos o takeProfitPrice do domínio
            takeProfit.lmtPrice(domainOrder.takeProfitPrice().doubleValue());
            takeProfit.totalQuantity(parent.totalQuantity());
            takeProfit.transmit(true);
            bracket.add(takeProfit);
        } else {
            if (bracket.size() > 1) {
                bracket.get(bracket.size() - 1).transmit(true);
            } else {
                parent.transmit(true);
            }
        }

        return bracket;
    }

    @Override
    public com.ib.client.Order toWhatIfOrder(int orderId, String side, int quantity) {
        com.ib.client.Order ibkrOrder = new com.ib.client.Order();
        ibkrOrder.orderId(orderId);
        ibkrOrder.action(side);
        ibkrOrder.totalQuantity(Decimal.get(Long.valueOf(quantity)));
        ibkrOrder.orderType("MKT");
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
        if (marginValue == null || marginValue.isEmpty()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(marginValue.replaceAll("[^0-9\\.\\-]", ""));
        } catch (Exception e) { return BigDecimal.ZERO; }
    }
}