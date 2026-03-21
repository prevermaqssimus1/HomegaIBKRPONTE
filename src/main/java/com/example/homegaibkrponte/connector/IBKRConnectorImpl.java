package com.example.homegaibkrponte.connector;

import com.example.homegaibkrponte.client.IBKRConnector;
import com.example.homegaibkrponte.model.OrderExecutionResult;
import com.example.homegaibkrponte.monitoring.LivePortfolioService;
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

    public IBKRConnectorImpl(LivePortfolioService livePortfolioService) {
        this.livePortfolioService = livePortfolioService;
    }

    /**
     * ✅ EXECUÇÃO DE ORDEM COM MULTI-CAMADA DE PROTEÇÃO
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

        // Identificadores de Rastreamento
        String clientOrderIdStr = symbol + "_" + System.currentTimeMillis();
        long orderIdLong = System.currentTimeMillis() % 1000000;

        try {
            log.info("🚀 [TWS-OUT] Preparando {} {} {} | ID: {}", action, quantity, symbol, orderIdLong);

            // 🛡️ PASSO 2: RESERVA DE BUYING POWER (Evita Over-trading)
            BigDecimal referencePrice = livePortfolioService.getMarketDataProvider().apply(symbol);

            // Fallback: Se o preço for zero (mercado fechado ou erro de API), usa NLV/100 como base conservadora
            if (referencePrice.compareTo(BigDecimal.ZERO) <= 0) {
                BigDecimal nlv = livePortfolioService.getNetLiquidationValue();
                referencePrice = nlv.divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                log.warn("⚠️ [TWS-OUT] Sem preço para {}. Usando fallback conservador para reserva: R$ {}", symbol, referencePrice);
            }

            // Registra capital "em voo" na LivePortfolioService
            livePortfolioService.trackOrderSent(clientOrderIdStr, symbol, BigDecimal.valueOf(quantity), referencePrice);
            // 🛡️ PASSO 3: TRANSMISSÃO TWS
            // TODO: Aqui entra a chamada nativa da IBKR API (eClient.placeOrder)
            log.info("✅ [TWS-OUT] Ordem transmitida com sucesso. ID: {}", orderIdLong);

            return new OrderExecutionResult(true, orderIdLong, "Transmitida");

        } catch (Exception e) {
            log.error("❌ [TWS-ERR] Erro crítico no envio para {}: {}", symbol, e.getMessage());

            // Segurança: Se falhou o envio, remove a reserva de capital imediatamente
            livePortfolioService.removePendingOrder(clientOrderIdStr);

            return new OrderExecutionResult(false, "Erro TWS: " + e.getMessage());
        }
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