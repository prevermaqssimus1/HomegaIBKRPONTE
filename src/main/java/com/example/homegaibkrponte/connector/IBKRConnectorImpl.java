package com.example.homegaibkrponte.connector;

import com.example.homegaibkrponte.client.IBKRConnector; // 🛡️ Import do "Coração"
import com.example.homegaibkrponte.connector.mapper.IBKRMapper;
import com.example.homegaibkrponte.factory.ContractFactory;
import com.example.homegaibkrponte.factory.OrderFactory;
import com.example.homegaibkrponte.model.OrderExecutionResult;
import com.example.homegaibkrponte.monitoring.LivePortfolioService;
import com.example.homegaibkrponte.service.OrderIdManager;
import com.ib.client.Contract;
import com.ib.client.Order;
import com.ib.client.EClientSocket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 🌉 **IBKR CONNECTOR IMPL (PONTE) - V26 DOUTRINA DE ELITE**
 * Implementação robusta do conector com foco em proteção de margem e rastreamento de capital.
 * SINERGIA: Agora com duto de saída conectado ao Socket Principal via heartConnector.
 */
@Slf4j
@Service
public class IBKRConnectorImpl implements com.example.homegaibkrponte.client.IBKRConnector {

    private final LivePortfolioService livePortfolioService;
    private final Map<String, Integer> recoveryTracker = new ConcurrentHashMap<>();
    private final IBKRMapper ibkrMapper;

    // Configurações de Risco e Recuperação
    private static final int MAX_RECOVERY_ATTEMPTS = 2;
    private static final BigDecimal MARGIN_VETO_LIMIT = new BigDecimal("0.90");
    private static final BigDecimal REDUCTION_FACTOR = new BigDecimal("0.60");

    private final OrderIdManager orderIdManager;
    private final OrderFactory orderFactory;
    private final ContractFactory contractFactory;

    // ⚔️ O Duto de Saída Real
    private final com.example.homegaibkrponte.connector.IBKRConnector heartConnector;

    public IBKRConnectorImpl(LivePortfolioService livePortfolioService,
                             IBKRMapper ibkrMapper,
                             OrderIdManager orderIdManager,
                             OrderFactory orderFactory,
                             ContractFactory contractFactory,
                             @Lazy com.example.homegaibkrponte.connector.IBKRConnector heartConnector) { // 🛡️ Injeção de Sinergia
        this.livePortfolioService = livePortfolioService;
        this.ibkrMapper = ibkrMapper;
        this.orderIdManager = orderIdManager;
        this.orderFactory = orderFactory;
        this.contractFactory = contractFactory;
        this.heartConnector = heartConnector;
    }

    /**
     * ✅ EXECUÇÃO DE ORDEM COM MULTI-CAMADA DE PROTEÇÃO
     */
    @Override
    public OrderExecutionResult placeOrder(String symbol, long quantity, String action, String orderType) {

        BigDecimal utilization = livePortfolioService.getMarginUtilization();
        if (utilization.compareTo(MARGIN_VETO_LIMIT) > 0) {
            log.error("🛡️ [VETO PREVENTIVO] Utilização de Margem Crítica: {}%. Bloqueando envio de {}.",
                    utilization.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP), symbol);
            return new OrderExecutionResult(false, "Veto por Margem Crítica (>90%)");
        }

        String clientOrderIdStr = "HEG-" + symbol + "-" + System.nanoTime();
        int nextOrderId = orderIdManager.getNextOrderId();

        try {
            log.info("🚀 [TWS-OUT] Preparando {} {} {} | ID Interno: {}", action, quantity, symbol, nextOrderId);

            BigDecimal referencePrice = livePortfolioService.getMarketDataProvider().apply(symbol);

            if (referencePrice == null || referencePrice.compareTo(BigDecimal.ZERO) <= 0) {
                referencePrice = livePortfolioService.getLastKnownPrice(symbol);
                if (referencePrice.compareTo(BigDecimal.ZERO) <= 0) {
                    log.error("🛑 [CRITICAL-PRICE-MISSING] Impossível determinar preço para {}. Abortando envio.", symbol);
                    return new OrderExecutionResult(false, "Cegueira de preço detectada");
                }
            }

            livePortfolioService.trackOrderSent(clientOrderIdStr, symbol, BigDecimal.valueOf(quantity), referencePrice);

            Contract contract = contractFactory.create(symbol);
            Order ibkrOrder = orderFactory.createSimple(action, quantity, referencePrice);
            ibkrOrder.orderId(nextOrderId);

            orderIdManager.linkIds(clientOrderIdStr, nextOrderId);

            // 🎯 DISPARO SINÉRGICO (Usa o duto do Coração)
            this.placeOrder(String.valueOf(nextOrderId), contract, ibkrOrder);

            log.info("✅ [TWS-OUT] Ordem {} para {} transmitida com sucesso.", nextOrderId, symbol);
            return new OrderExecutionResult(true, (long) nextOrderId, "Transmitida");

        } catch (Exception e) {
            log.error("❌ [TWS-ERR] Erro crítico no envio para {}: {}", symbol, e.getMessage());
            livePortfolioService.removePendingOrderById(clientOrderIdStr);
            return new OrderExecutionResult(false, "Erro TWS: " + e.getMessage());
        }
    }

    /**
     * ✅ O DUTO DE SAÍDA FINAL (Override Obrigatório)
     * Não reescreve a lógica de rede, delega para quem tem o socket ativo.
     */
    @Override
    public void placeOrder(String orderId, Contract contract, Order order) {
        if (isConnected()) {
            log.info("📤 [TWS-DISPATCH] Delegando Ordem #{} ({}) ao Socket Principal.", orderId, contract.symbol());
            // 🛡️ CHAMA O CLIENTE REAL DENTRO DO CORAÇÃO
            heartConnector.getClient().placeOrder(Integer.parseInt(orderId), contract, order);
        } else {
            log.error("❌ [TWS-DISPATCH-FAIL] Gateway desconectado! Ordem #{} ABORTADA.", orderId);
            livePortfolioService.removePendingOrderById(order.orderRef());
        }
    }

    public List<Order> toBracketOrder(com.example.homegaibkrponte.model.Order domainOrder) {
        List<Order> bracket = new ArrayList<>();
        Order parent = ibkrMapper.toIBKROrder(domainOrder);
        parent.transmit(false);
        bracket.add(parent);

        // ⚠️ CORREÇÃO: deriva exitAction diretamente de domainOrder.isCompra()
        // em vez de depender do resultado de parent.getAction() — mesmo já
        // corrigido via toIBKROrder (que agora usa isCompra() corretamente),
        // isso remove a dependência frágil entre os dois métodos.
        String exitAction = domainOrder.isCompra() ? "SELL" : "BUY";

        if (domainOrder.stopLossPrice() != null && domainOrder.stopLossPrice().signum() > 0) {
            Order stopLoss = new Order();
            stopLoss.orderId(orderIdManager.getNextOrderId());
            stopLoss.parentId(parent.orderId());
            stopLoss.action(exitAction);
            stopLoss.orderType("STP");
            stopLoss.auxPrice(domainOrder.stopLossPrice().doubleValue());
            stopLoss.totalQuantity(parent.totalQuantity());
            stopLoss.transmit(false);
            bracket.add(stopLoss);
        }

        if (domainOrder.takeProfitPrice() != null && domainOrder.takeProfitPrice().signum() > 0) {
            Order takeProfit = new Order();
            takeProfit.orderId(orderIdManager.getNextOrderId());
            takeProfit.parentId(parent.orderId());
            takeProfit.action(exitAction);
            takeProfit.orderType("LMT");
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
    public boolean isConnected() {
        return heartConnector != null && heartConnector.isConnected();
    }

    // Métodos de recuperação (onOrderError, handleMarginRecovery, etc.) permanecem inalterados...

    public void onOrderError(String clientOrderId, int errorCode, String errorMsg) {
        try {
            log.warn("⚠️ [IBKR CALLBACK] Erro recebido: Código {} | Mensagem: {} | ID: {}", errorCode, errorMsg, clientOrderId);
            livePortfolioService.removePendingOrder(clientOrderId);
            if (errorCode == 201) {
                log.error("🛑 [MARGEM] Rejeição Crítica na IBKR. Iniciando mitigação para {}", clientOrderId);
                handleMarginRecovery(clientOrderId);
            }
        } catch (Exception e) {
            log.error("❌ Erro no callback de erro: {}", e.getMessage());
        }
    }

    private void handleMarginRecovery(String clientOrderId) {
        String symbol = extractSymbol(clientOrderId);
        if ("UNKNOWN".equals(symbol)) return;
        int attempts = recoveryTracker.getOrDefault(symbol, 0);
        if (attempts >= MAX_RECOVERY_ATTEMPTS) {
            log.error("🛑 [RECOVERY FATAL] Abortando {} após {} tentativas.", symbol, attempts);
            recoveryTracker.remove(symbol);
            return;
        }
        recoveryTracker.put(symbol, attempts + 1);
        log.warn("🔄 [RECOVERY] Reduzindo lote para {} (Tentativa {}/{}).", symbol, attempts + 1, MAX_RECOVERY_ATTEMPTS);
    }

    private String extractSymbol(String clientOrderId) {
        try {
            if (clientOrderId != null && clientOrderId.contains("-")) {
                return clientOrderId.split("-")[1]; // Ajustado para o prefixo HEG-SYMBOL-
            }
        } catch (Exception e) {
            log.error("❌ Falha ao extrair símbolo do ID: {}", clientOrderId);
        }
        return "UNKNOWN";
    }
}
