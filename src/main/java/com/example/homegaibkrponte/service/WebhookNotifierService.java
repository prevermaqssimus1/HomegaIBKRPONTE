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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 🛰️ WEBHOOK NOTIFIER SERVICE - VERSÃO CONSOLIDADA V5.5 (SNIPER + RISK)
 * Equalização total: Combina Latência Mínima (Sniper) com Telemetria de Risco.
 */
@Service
@Slf4j
public class WebhookNotifierService {

    private final WebClient webClient;

    private static final String EXECUTION_STATUS_URI = "/api/v1/callbacks/ibkr/order-fill";
    private static final String REJECTION_URI = "/api/v1/callbacks/ibkr/order-rejection";
    private static final String MARKET_TICK_URI = "/api/bridge/data/tick";
    private static final String RISK_SYNC_URI = "/api/risk/sync-adjustment";
    private static final String LIQUIDITY_ALERT_URI = "/webhook/alert/liquidity";

    // No topo da classe
    private final ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

    // ⚡ RETRY SNIPER: 3 tentativas com backoff de 200ms para liberar threads rapidamente.
    private final Retry fastRetry = Retry.backoff(3, Duration.ofMillis(200));

    public WebhookNotifierService(
            @Value("${homega.app.webhook.base-url:http://127.0.0.1:8080}") String baseUrl
    ) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();
        log.info("🔔 [PONTE-ESTÁVEL] Sniper & Risk Manager ativo em: {}", baseUrl);
    }

    /**
     * 🎌 ENVIO DE TICK SNIPER (Ultra Baixa Latência)
     */
    /**
     * 🎌 ENVIO DE TICK SNIPER (Ultra Baixa Latência - Passo 5)
     * Ajustado para Fire-and-Forget total.
     */
    public void sendMarketTick(String symbol, BigDecimal price, BigDecimal bid, BigDecimal ask, Long size) {
        virtualExecutor.submit(() -> {
            try {
                BigDecimal validBid = (bid != null) ? bid : price;
                BigDecimal validAsk = (ask != null) ? ask : price;
                Long timestamp = System.currentTimeMillis();

                MarketTickDTO tick = new MarketTickDTO(symbol, price, validBid, validAsk, size != null ? size : 0L, timestamp);

                this.webClient.post()
                        .uri(MARKET_TICK_URI)
                        .bodyValue(tick)
                        .retrieve()
                        .toBodilessEntity()
                        .timeout(Duration.ofMillis(100)) // ⚡ Timeout agressivo: Se o Principal não ouvir em 100ms, ignore.
                        .subscribe(
                                null,
                                err -> {} // Silêncio total em erro de tick para poupar log
                        );
            } catch (Exception e) {
                // Engole a exceção: Ticks são voláteis, o próximo chegará em ms.
            }
        });
    }

    /**
     * 🛡️ TELEMETRIA DE ADAPTIVE CHECK (What-If)
     * Notifica quando o AMC reduz quantidades preventivamente.
     */
    public void sendAdaptiveCheckAlert(String symbol, double originalQty, double reducedQty, String elAfter) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "PREVENTIVE_REDUCTION");
        payload.put("symbol", symbol);
        payload.put("originalQuantity", originalQty);
        payload.put("actualQuantity", reducedQty);
        payload.put("projectedExcessLiquidity", elAfter);
        payload.put("timestamp", LocalDateTime.now().toString());

        this.webClient.post()
                .uri(RISK_SYNC_URI)
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .retryWhen(fastRetry)
                .subscribe(
                        res -> log.info("✅ [AMC-SYNC] Telemetria enviada: {}", symbol),
                        err -> log.error("❌ [AMC-SYNC] Falha no reporte de redução: {}", symbol)
                );
    }

    /**
     * 🚀 REJEIÇÃO (Sinergizada) - Usa fastRetry para liberar capital no Winston
     */
    public void sendOrderRejection(String clientOrderId, long brokerOrderId, int errorCode, String reason) {
        OrderRejectionDto rejection = new OrderRejectionDto(clientOrderId, brokerOrderId, errorCode, reason);
        enviarRejeicao(rejection);
    }

    public void sendOrderRejection(long orderId, int errorCode, String reason) {
        OrderRejectionDto rejection = new OrderRejectionDto(orderId, errorCode, reason);
        enviarRejeicao(rejection);
    }

    private void enviarRejeicao(OrderRejectionDto dto) {
        this.webClient.post()
                .uri(REJECTION_URI)
                .bodyValue(dto)
                .retrieve()
                .toBodilessEntity()
                .retryWhen(fastRetry)
                .subscribe(
                        success -> log.info("✅ [REJECT-OUT] Entregue: {}", dto.getClientOrderId() != null ? dto.getClientOrderId() : dto.getOrderId()),
                        err -> log.error("❌ [REJECT-OUT] Falha fatal na entrega do erro.")
                );
    }

    /**
     * 💸 EXECUÇÃO (FILL) - Sincronia de saldo real
     */
    /**
     * 💸 EXECUÇÃO (FILL) - Passo 5
     * Envio isolado para garantir que a thread da TWS volte a operar instantaneamente.
     */
    public void sendExecutionReport(ExecutionReportDto report) {
        virtualExecutor.submit(() -> {
            this.webClient.post()
                    .uri(EXECUTION_STATUS_URI)
                    .bodyValue(report)
                    .retrieve()
                    .toBodilessEntity()
                    .retryWhen(fastRetry) // Mantém o retry para integridade do saldo
                    .subscribe(
                            res -> log.info("✅ [FILL-SYNC] {} reportado via VirtualThread.", report.getSymbol()),
                            err -> log.error("❌ [FILL-SYNC] Falha crítica ao sincronizar execução após retries.")
                    );
        });
    }
    /**
     * ⚠️ ALERTAS DE LIQUIDEZ (Warnings e Critical)
     */
    public void notifyWarningLiquidity(String message) {
        sendLiquidityAlert("WARNING", message);
    }

    public void notifyCriticalLiquidity(String message) {
        sendLiquidityAlert("CRITICAL", message);
    }

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
                        res -> log.warn("🚨 [ALERTA-{}] Sincronizado com Principal.", level),
                        err -> log.trace("Falha silenciada no alerta de liquidez.")
                );
    }
}