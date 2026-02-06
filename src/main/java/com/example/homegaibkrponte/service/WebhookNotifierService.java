package com.example.homegaibkrponte.service;

import com.example.homegaibkrponte.dto.ExecutionReportDto;
import com.example.homegaibkrponte.dto.MarketTickDTO;
import com.example.homegaibkrponte.dto.OrderRejectionDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * SERVIÇO NA PONTE IBKR (BRIDGE)
 * Responsável por notificar a Aplicação Principal sobre eventos críticos.
 * * ✅ FASE 4: Integrado com telemetria de Risco Adaptativo (AMC).
 */
@Service
@Slf4j
public class WebhookNotifierService {

    private final WebClient webClient;

    // --- URIs DE SINERGIA ---
    private static final String EXECUTION_STATUS_URI = "/webhook/execution-status";
    private static final String MARKET_TICK_URI = "/api/v1/callbacks/ibkr/market-tick";
    private static final String RISK_SYNC_URI = "/api/risk/sync-adjustment"; // ✅ Endpoint da Fase 2/4
    private static final String LIQUIDITY_ALERT_URI = "/webhook/alert/liquidity";

    // Política de Retentativa: 3 tentativas com backoff exponencial
    private final Retry retrySpec = Retry.backoff(3, Duration.ofSeconds(2))
            .doBeforeRetry(retrySignal -> log.warn(
                    "⚠️ [WEBHOOK-OUT] Falha de comunicação. Tentativa #{} de 3. Causa: {}",
                    retrySignal.totalRetries() + 1, retrySignal.failure().getMessage()
            ));

    public WebhookNotifierService(
            @Value("${homega.app.webhook.base-url:http://localhost:8080}") String baseUrl
    ) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();
        log.info("🔔 [PONTE] WebhookNotifierService configurado para: {}", baseUrl);
    }

    /**
     * ✅ FASE 4: Notifica o Principal sobre reduções preventivas (What-If).
     * Este método fecha o loop de observabilidade.
     */
    public void sendAdaptiveCheckAlert(String symbol, double originalQty, double reducedQty, String elAfter) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "PREVENTIVE_REDUCTION");
        payload.put("symbol", symbol);
        payload.put("originalQuantity", originalQty);
        payload.put("actualQuantity", reducedQty);
        payload.put("projectedExcessLiquidity", elAfter);
        payload.put("timestamp", LocalDateTime.now().toString());

        log.warn("📢 [WEBHOOK-OUT] Enviando telemetria AMC: {} reduzido de {} para {} | EL: {} -> URL: {}",
                symbol, originalQty, reducedQty, elAfter, RISK_SYNC_URI);

        this.webClient.post()
                .uri(RISK_SYNC_URI)
                .bodyValue(payload)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> Mono.error(new RuntimeException("Erro: " + response.statusCode())))
                .toBodilessEntity()
                .retryWhen(retrySpec)
                .subscribe(
                        response -> log.info("✅ [WEBHOOK-OUT] Telemetria AMC para {} confirmada.", symbol),
                        error -> log.error("❌ [WEBHOOK-OUT] Falha permanente ao enviar telemetria AMC: {}", error.getMessage())
                );
    }

    /**
     * Envia a rejeição da ordem (Erro 201 ou outros).
     */
    public void sendOrderRejection(long orderId, int errorCode, String reason) {
        OrderRejectionDto rejection = new OrderRejectionDto(orderId, errorCode, reason);
        log.error("🚨 [WEBHOOK-OUT] Enviando REJEIÇÃO (ID: {}) Cód: {} -> URL: {}", orderId, errorCode, EXECUTION_STATUS_URI);

        this.webClient.post()
                .uri(EXECUTION_STATUS_URI)
                .bodyValue(rejection)
                .retrieve()
                .toBodilessEntity()
                .retryWhen(retrySpec)
                .subscribe(
                        res -> log.info("✅ [WEBHOOK-OUT] Notificação de REJEIÇÃO {} confirmada.", orderId),
                        err -> log.error("❌ [WEBHOOK-OUT] Falha ao notificar rejeição {}: {}", orderId, err.getMessage())
                );
    }

    /**
     * Envia o relatório de execução real.
     */
    public void sendExecutionReport(ExecutionReportDto report) {
        log.info("▶️ [WEBHOOK-OUT] Enviando execução Ordem: {} -> URL: {}", report.getOrderId(), EXECUTION_STATUS_URI);

        this.webClient.post()
                .uri(EXECUTION_STATUS_URI)
                .bodyValue(report)
                .retrieve()
                .toBodilessEntity()
                .retryWhen(retrySpec)
                .subscribe(
                        res -> log.info("✅ [WEBHOOK-OUT] Execução da ordem {} confirmada.", report.getOrderId()),
                        err -> log.error("❌ [WEBHOOK-OUT] Falha ao notificar execução {}: {}", report.getOrderId(), err.getMessage())
                );
    }

    /**
     * Envia o tick de mercado para o sistema principal.
     */
    public void sendMarketTick(String symbol, BigDecimal price) {
        MarketTickDTO tick = new MarketTickDTO(symbol, price);
        this.webClient.post()
                .uri(MARKET_TICK_URI)
                .bodyValue(tick)
                .retrieve()
                .toBodilessEntity()
                .subscribe(
                        res -> log.trace("✅ [WEBHOOK-OUT] Market Tick {} confirmado.", symbol),
                        err -> log.trace("❌ Falha Market Tick {}.", symbol)
                );
    }

    // --- NOTIFICAÇÕES DE LIQUIDEZ ---

    public void notifyCriticalLiquidity(String message) { sendLiquidityAlert("CRITICAL", message); }
    public void notifyWarningLiquidity(String message) { sendLiquidityAlert("WARNING", message); }

    private void sendLiquidityAlert(String level, String message) {
        Map<String, Object> alert = new HashMap<>();
        alert.put("level", level);
        alert.put("message", message);
        alert.put("timestamp", LocalDateTime.now().toString());

        this.webClient.post()
                .uri(LIQUIDITY_ALERT_URI)
                .bodyValue(alert)
                .retrieve()
                .toBodilessEntity()
                .subscribe(
                        res -> log.info("✅ [WEBHOOK-OUT] Alerta de liquidez {} confirmado.", level),
                        err -> log.error("❌ [WEBHOOK-OUT] Falha no alerta de liquidez: {}", err.getMessage())
                );
    }

    public void sendOrderCancellation(long orderId, String clientOrderId) {
        // Código 202 na IBKR significa "Cancelled"
        OrderRejectionDto rejection = new OrderRejectionDto(orderId, 202, "Order cancelled to release Buying Power");

        log.warn("🧹 [PONTE -> WINSTON] Notificando CANCELAMENTO da ordem {} para liberar saldo.", clientOrderId);

        this.webClient.post()
                .uri(EXECUTION_STATUS_URI) // Mesmo endpoint que o Winston já ouve
                .bodyValue(rejection)
                .retrieve()
                .toBodilessEntity()
                .retryWhen(retrySpec)
                .subscribe(
                        res -> log.info("✅ [WEBHOOK-OUT] Cancelamento confirmado no Principal."),
                        err -> log.error("❌ [WEBHOOK-OUT] Falha ao notificar cancelamento.")
                );
    }

}