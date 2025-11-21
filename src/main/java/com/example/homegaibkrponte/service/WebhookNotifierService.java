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
import java.time.LocalDateTime; // NOVO IMPORT NECESSÁRIO
import java.util.HashMap; // NOVO IMPORT NECESSÁRIO
import java.util.Map; // NOVO IMPORT NECESSÁRIO


/**
 * SERVIÇO NA PONTE IBKR (BRIDGE)
 * Responsável por notificar a Aplicação Principal (Main) sobre eventos críticos (execução, rejeição, tick) via Webhooks.
 *
 * Utiliza WebClient para comunicação reativa e robusta, com política de retries definida.
 */
@Service
@Slf4j
public class WebhookNotifierService {

    private final WebClient webClient;

    // 🚨 AJUSTE CRÍTICO DE SINERGIA: UNIFICANDO OS URIS PARA O WEBHOOK DINÂMICO NO PRINCIPAL
    private static final String EXECUTION_STATUS_URI = "/webhook/execution-status";

    // O Market Tick é um evento separado e precisa ser tratado em um endpoint dedicado
    private static final String MARKET_TICK_URI = "/api/v1/callbacks/ibkr/market-tick";

    // ✅ NOVO URI: Para notificações de Liquidez/Saúde
    private static final String LIQUIDITY_ALERT_URI = "/webhook/alert/liquidity";

    // Política de Retentativa: 3 tentativas, com backoff exponencial a partir de 2 segundos.
    private final Retry retrySpec = Retry.backoff(3, Duration.ofSeconds(2))
            .doBeforeRetry(retrySignal -> log.warn(
                    "⚠️ [WEBHOOK-OUT] Falha de comunicação com Principal. Tentativa #{} de 3. Causa: {}",
                    retrySignal.totalRetries() + 1,
                    retrySignal.failure().getMessage()
            ))
            .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> {
                log.error("❌ [WEBHOOK-OUT - ERRO CRÍTICO] Retries esgotados para o Webhook. Causa final: {}", retrySignal.failure().getMessage());
                // Lançar exceção para ser capturada e logada no método chamador
                return new IllegalStateException(
                        "Falha definitiva no envio do Webhook após 3 retries.",
                        retrySignal.failure()
                );
            });

    public WebhookNotifierService(
            @Value("${homega.app.webhook.base-url:http://localhost:8080}") String baseUrl
    ) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();
        log.info("🔔 [PONTE] WebhookNotifierService configurado. URL Base do Principal: {}", baseUrl);
    }

    /**
     * Envia a notificação de Relatório de Execução para a Aplicação Principal.
     * @param report DTO com os detalhes da execução.
     */
    public void sendExecutionReport(ExecutionReportDto report) {
        log.info("▶️ [WEBHOOK-OUT] Enviando notificação de execução para o Principal. Ordem: {} -> URL: {}", report.getOrderId(), EXECUTION_STATUS_URI);

        this.webClient.post()
                .uri(EXECUTION_STATUS_URI)
                .bodyValue(report)
                .retrieve()
                .onStatus(HttpStatusCode::isError, clientResponse ->
                        Mono.error(new RuntimeException("Principal retornou erro HTTP: " + clientResponse.statusCode()))
                )
                .toBodilessEntity()
                .retryWhen(retrySpec)
                .subscribe(
                        response -> log.info(
                                "✅ [WEBHOOK-OUT] Notificação de Execução da ordem {} confirmada pelo Principal (Status: {}).",
                                report.getOrderId(),
                                response.getStatusCode()
                        ),
                        // Captura a exceção se os retries falharem definitivamente
                        error -> log.error(
                                "❌ [WEBHOOK-OUT - FALHA PERMANENTE] Falha definitiva ao notificar Execução da ordem {}. Liquidez comprometida até ajuste manual. Causa: {}",
                                report.getOrderId(),
                                error.getMessage()
                        )
                );
    }

    /**
     * Envia a rejeição da ordem pela corretora ao Principal.
     * @param orderId O ID da Ordem na Aplicação Principal (Client ID na Ponte).
     * @param errorCode O código de erro da corretora (ex: 201).
     * @param reason A mensagem de rejeição.
     */
    public void sendOrderRejection(long orderId, int errorCode, String reason) {
        OrderRejectionDto rejection = new OrderRejectionDto(orderId, errorCode, reason);

        log.error("🚨 [WEBHOOK-OUT] Enviando REJEIÇÃO CRÍTICA (ID: {}) para o Principal. Cód: {} -> URL: {}",
                orderId, errorCode, EXECUTION_STATUS_URI);

        this.webClient.post()
                .uri(EXECUTION_STATUS_URI)
                .bodyValue(rejection)
                .retrieve()
                .onStatus(HttpStatusCode::isError, clientResponse ->
                        Mono.error(new RuntimeException("Principal retornou erro HTTP: " + clientResponse.statusCode()))
                )
                .toBodilessEntity()
                .retryWhen(retrySpec)
                .subscribe(
                        response -> log.info(
                                "✅ [WEBHOOK-OUT] Notificação de REJEIÇÃO (ID: {}) confirmada pelo Principal (Status: {}).",
                                orderId,
                                response.getStatusCode()
                        ),
                        error -> log.error(
                                "❌ [WEBHOOK-OUT - FALHA PERMANENTE] Falha definitiva ao notificar REJEIÇÃO da ordem {}. Capital pode permanecer comprometido. Causa: {}",
                                orderId,
                                error.getMessage()
                        )
                );
    }

    /**
     * Envia o tick de preço da Ponte para o Principal.
     */
    public void sendMarketTick(String symbol, BigDecimal price) {
        MarketTickDTO tick = new MarketTickDTO(symbol, price);
        log.trace("▶️ [WEBHOOK-OUT] Enviando Market Tick para {} (R${}) para o Principal.", symbol, price);

        this.webClient.post()
                .uri(MARKET_TICK_URI)
                .bodyValue(tick)
                .retrieve()
                .onStatus(HttpStatusCode::isError, clientResponse ->
                        Mono.error(new RuntimeException("Principal retornou erro HTTP: " + clientResponse.statusCode()))
                )
                .toBodilessEntity()
                .retryWhen(Retry.backoff(2, Duration.ofMillis(500)))
                .subscribe(
                        response -> log.trace("✅ [WEBHOOK-OUT] Market Tick para {} confirmado (Status: {}).", symbol, response.getStatusCode()),
                        error -> log.warn("❌ Falha temporária ao enviar Market Tick para {}: {}", symbol, error.getMessage())
                );
    }

    // =========================================================================
    // ✅ NOVOS MÉTODOS PARA NOTIFICAÇÃO DE LIQUIDEZ (FIXANDO SINERGIA)
    // =========================================================================

    /**
     * Envia uma notificação crítica de liquidez ao Principal (usada por LiquidityMonitorService).
     */
    public void notifyCriticalLiquidity(String message) {
        sendLiquidityAlert("CRITICAL", message);
    }

    /**
     * Envia uma notificação de warning/alerta de liquidez ao Principal.
     */
    public void notifyWarningLiquidity(String message) {
        sendLiquidityAlert("WARNING", message);
    }

    private void sendLiquidityAlert(String level, String message) {
        Map<String, Object> alert = new HashMap<>();
        alert.put("level", level);
        alert.put("message", message);
        alert.put("timestamp", LocalDateTime.now());

        log.warn("🚨 [WEBHOOK-OUT] Enviando alerta de liquidez {} -> URL: {}", level, LIQUIDITY_ALERT_URI);

        // Política de Retry mais leve, pois é um alerta, não uma execução transacional
        Retry alertRetrySpec = Retry.backoff(2, Duration.ofSeconds(1));

        this.webClient.post()
                .uri(LIQUIDITY_ALERT_URI)
                .bodyValue(alert)
                .retrieve()
                .onStatus(HttpStatusCode::isError, clientResponse ->
                        Mono.error(new RuntimeException("Principal retornou erro HTTP: " + clientResponse.statusCode()))
                )
                .toBodilessEntity()
                .retryWhen(alertRetrySpec)
                .subscribe(
                        response -> log.info("✅ [WEBHOOK-OUT] Alerta de liquidez {} confirmado.", level),
                        error -> log.error("❌ [WEBHOOK-OUT] Falha ao notificar alerta de liquidez {}. Causa: {}", level, error.getMessage())
                );
    }
}