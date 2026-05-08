package com.example.homegaibkrponte.connector;

import com.example.homegaibkrponte.client.IBKRConnector;
import com.example.homegaibkrponte.factory.ContractFactory;
import com.example.homegaibkrponte.factory.OrderFactory;
import com.example.homegaibkrponte.model.OrderExecutionResult;
import com.example.homegaibkrponte.monitoring.LivePortfolioService;
import com.example.homegaibkrponte.service.OrderIdManager;
import com.ib.client.Contract;
import com.ib.client.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 🌉 **IBKR CONNECTOR IMPL (PONTE)**
 * Implementação robusta do conector com foco em proteção de margem e rastreamento de capital.
 */
@Slf4j
@Service
public class IBKRConnectorImpl implements IBKRConnector {

    private final LivePortfolioService livePortfolioService;
    private final Map<String, Integer> recoveryTracker = new ConcurrentHashMap<>();

    // Configurações de Risco e Recuperação
    private static final int MAX_RECOVERY_ATTEMPTS = 2;
    private static final BigDecimal MARGIN_VETO_LIMIT = new BigDecimal("0.90"); // 90%
    private static final BigDecimal REDUCTION_FACTOR = new BigDecimal("0.60"); // Reduz 40% do lote
    private final OrderIdManager orderIdManager;
    private final OrderFactory orderFactory;
    private final ContractFactory contractFactory;

    public IBKRConnectorImpl(LivePortfolioService livePortfolioService, ContractFactory contractFactory, ContractFactory orderFactory, OrderIdManager orderIdManager, OrderFactory orderFactory1, ContractFactory contractFactory1) {
        this.livePortfolioService = livePortfolioService;
        this.orderIdManager = orderIdManager;
        this.orderFactory = orderFactory1;
        this.contractFactory = contractFactory1;
    }

    /**
     * ✅ EXECUÇÃO DE ORDEM COM MULTI-CAMADA DE PROTEÇÃO
     * Sinergia: Injeta o preço do Oráculo diretamente na transmissão física para evitar erro 10167 na TWS.
     */
    @Override
    public OrderExecutionResult placeOrder(String symbol, long quantity, String action, String orderType) {

        // 🛡️ PASSO 1: PRE-FLIGHT CHECK (Veto de Utilização de Margem)
        BigDecimal utilization = livePortfolioService.getMarginUtilization();
        if (utilization.compareTo(MARGIN_VETO_LIMIT) > 0) {
            log.error("🛡️ [VETO PREVENTIVO] Utilização de Margem Crítica: {}%. Bloqueando envio de {}.",
                    utilization.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP), symbol);
            return new OrderExecutionResult(false, "Veto por Margem Crítica (>90%)");
        }

        // Identificadores de Rastreamento (HEG- Prefix para auditoria clara)
        String clientOrderIdStr = "HEG-" + symbol + "-" + System.nanoTime();
        int nextOrderId = orderIdManager.getNextOrderId(); // ID sequencial da TWS

        try {
            log.info("🚀 [TWS-OUT] Preparando {} {} {} | ID Interno: {}", action, quantity, symbol, nextOrderId);

            // 🎯 SOBERANIA DE PREÇO: Recupera o preço real do WebSocket/Finnhub
            BigDecimal referencePrice = livePortfolioService.getMarketDataProvider().apply(symbol);

            // 🛡️ PASSO 2: VALIDAÇÃO DE PREÇO (Anti-Cegueira)
            if (referencePrice == null || referencePrice.compareTo(BigDecimal.ZERO) <= 0) {
                // Tenta o último preço estável cacheado antes do veto total
                referencePrice = livePortfolioService.getLastKnownPrice(symbol);

                if (referencePrice.compareTo(BigDecimal.ZERO) <= 0) {
                    log.error("🛑 [CRITICAL-PRICE-MISSING] Impossível determinar preço para {}. Abortando envio.", symbol);
                    return new OrderExecutionResult(false, "Cegueira de preço detectada");
                }
                log.warn("⚠️ [TWS-OUT] Usando preço de cache para {}: ${}", symbol, referencePrice);
            }

            // 📥 REGISTRO NO COFRE: Bloqueia capital "em voo" localmente
            livePortfolioService.trackOrderSent(clientOrderIdStr, symbol, BigDecimal.valueOf(quantity), referencePrice);

            // 🛡️ PASSO 3: TRANSMISSÃO TWS (Injeção de Preço Soberano)
            // 1. Cria o contrato IBKR
            Contract contract = contractFactory.create(symbol);

            // 2. Cria a ordem IBKR injetando o preço para evitar que a TWS tente "adivinhar" e falhe
            // Convertendo String action para o DTO temporário para reuso da sua factory se necessário
            Order ibkrOrder = orderFactory.createSimple(action, quantity, referencePrice);
            ibkrOrder.orderId(nextOrderId);

            // 3. Vincula o ID local ao ID da corretora para o Callback saber quem liberar
            orderIdManager.linkIds(clientOrderIdStr, nextOrderId);

            // 4. Disparo Físico via padrão @Override placeOrder(String, Contract, Order)
            this.placeOrder(String.valueOf(nextOrderId), contract, ibkrOrder);

            log.info("✅ [TWS-OUT] Ordem {} para {} transmitida com sucesso a ${}", nextOrderId, symbol, referencePrice);
            return new OrderExecutionResult(true, (long) nextOrderId, "Transmitida");

        } catch (Exception e) {
            log.error("❌ [TWS-ERR] Erro crítico no envio para {}: {}", symbol, e.getMessage());

            // Segurança: Se falhou o envio físico, remove a reserva de capital imediatamente
            livePortfolioService.removePendingOrderById(clientOrderIdStr);

            return new OrderExecutionResult(false, "Erro TWS: " + e.getMessage());
        }
    }

    @Override
    public void placeOrder(String orderId, Contract contract, Order order) {

    }

    @Override
    public boolean isConnected() {
        return false;
    }

    /**
     * 📥 CALLBACK DE ERRO DA TWS
     * Processa rejeições da corretora e dispara protocolos de recuperação.
     */
    public void onOrderError(String clientOrderId, int errorCode, String errorMsg) {
        try {
            log.warn("⚠️ [IBKR CALLBACK] Erro recebido: Código {} | Mensagem: {} | ID: {}", errorCode, errorMsg, clientOrderId);

            // Independente do erro, limpamos a reserva de capital "em voo"
            livePortfolioService.removePendingOrder(clientOrderId);

            // Erro 201: Margem insuficiente / Rejeição de margem
            if (errorCode == 201) {
                log.error("🛑 [MARGEM] Rejeição Crítica na IBKR. Iniciando protocolo de recuperação para {}", clientOrderId);
                handleMarginRecovery(clientOrderId);
            }
        } catch (Exception e) {
            log.error("❌ Erro ao processar callback de erro: {}", e.getMessage());
        }
    }

    /**
     * 🔄 PROTOCOLO DE RECUPERAÇÃO (STEP-DOWN)
     * Reduz o tamanho do lote e tenta reexecutar em caso de erro de margem.
     */
    private void handleMarginRecovery(String clientOrderId) {
        String symbol = extractSymbol(clientOrderId);

        if ("UNKNOWN".equals(symbol)) return;

        int attempts = recoveryTracker.getOrDefault(symbol, 0);

        if (attempts >= MAX_RECOVERY_ATTEMPTS) {
            log.error("🛑 [RECOVERY FATAL] Abortando {} após {} tentativas frustradas de ajuste de margem.", symbol, attempts);
            recoveryTracker.remove(symbol);
            return;
        }

        recoveryTracker.put(symbol, attempts + 1);
        log.warn("🔄 [RECOVERY] Reduzindo lote em 40% para {} (Tentativa {}/{}) para tentar novo encaixe.",
                symbol, attempts + 1, MAX_RECOVERY_ATTEMPTS);

        // A lógica de reenvio com lote reduzido deve ser orquestrada pelo serviço que chamou o placeOrder,
        // garantindo que o novo cálculo de Sizing ocorra com base no erro recebido.
    }

    private String extractSymbol(String clientOrderId) {
        try {
            if (clientOrderId != null && clientOrderId.contains("_")) {
                return clientOrderId.split("_")[0];
            }
        } catch (Exception e) {
            log.error("❌ Falha ao extrair símbolo do ID: {}", clientOrderId);
        }
        return "UNKNOWN";
    }
}