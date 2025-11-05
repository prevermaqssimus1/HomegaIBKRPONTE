package com.example.homegaibkrponte.service;

import com.example.homegaibkrponte.dto.ExecutionReportDTO;
import com.example.homegaibkrponte.dto.MarketTickDTO;
import com.example.homegaibkrponte.dto.OrderRejectionDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.time.Duration;

@Service
@Slf4j
public class WebhookNotifierService {

    private final WebClient webClient;
    private final String executionWebhookUri; // URI para envio de execução
    private final String rejectionWebhookUri; // URI para envio de rejeição

    // URL base da aplicação principal (homega.app.url)
    private static final String BASE_URL_DEFAULT = "http://localhost:8080/api/v1/callbacks/ibkr";

    public WebhookNotifierService(
            // A melhor prática é injetar a URL base e não o endpoint completo
            @Value("${homega.app.webhook.base-url:http://localhost:8080}") String baseUrl
    ) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();

        // URLs específicas (Paths)
        this.executionWebhookUri = "/api/v1/callbacks/ibkr/execution-report";
        this.rejectionWebhookUri = "/api/v1/callbacks/ibkr/order-rejection"; // Endpoint a ser criado no Principal

        log.info("Webhook Notifier configurado. Base URL: {}", baseUrl);
    }

    public void sendExecutionReport(ExecutionReportDTO report) {
        log.warn("▶️  [WEBHOOK-OUT] Enviando notificação de execução para a aplicação principal. Ordem: {}", report.orderId());

        webClient.post()
                .uri(this.executionWebhookUri) // Uso da URI específica
                .bodyValue(report)
                .retrieve()
                .toBodilessEntity()
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                        .doBeforeRetry(retrySignal -> log.warn(
                                "Falha ao enviar webhook para ordem {}. Tentando novamente... (Tentativa {} de 3)",
                                report.orderId(),
                                retrySignal.totalRetries() + 1
                        ))
                        .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> new IllegalStateException(
                                "Retries exhausted: " + retrySignal.totalRetries() + "/" + 3,
                                retrySignal.failure()
                        ))
                )
                .subscribe(
                        response -> log.info(
                                "✅ [WEBHOOK-OUT] Notificação para ordem {} confirmada pela aplicação principal (Status: {}).",
                                report.orderId(),
                                response.getStatusCode()
                        ),
                        error -> log.error(
                                "❌ Falha definitiva ao enviar notificação de webhook para a ordem {}: {}",
                                report.orderId(),
                                error.getMessage()
                        )
                );
    }

    /**
     * 🚨 NOVO MÉTODO CRÍTICO DE SINERGIA: Envia a rejeição da ordem pela corretora ao Principal.
     * @param brokerOrderId O ID da corretora associado à ordem rejeitada.
     * @param errorCode O código de erro da corretora (ex: 201).
     * @param reason A mensagem de rejeição.
     */
    public void sendOrderRejection(int brokerOrderId, int errorCode, String reason) {
        // 1. Criamos um DTO para encapsular os dados de rejeição.
        // Crie esta classe/record em com.example.homegaibkrponte.dto
        OrderRejectionDTO rejection = new OrderRejectionDTO(brokerOrderId, errorCode, reason);

        log.error("🚨 [WEBHOOK-OUT] Enviando REJEIÇÃO CRÍTICA (ID: {}) para a aplicação principal. Cód: {}.", brokerOrderId, errorCode);

        webClient.post()
                .uri(this.rejectionWebhookUri) // Uso da URI específica
                .bodyValue(rejection)
                .retrieve()
                .toBodilessEntity()
                .subscribe(
                        response -> log.info(
                                "✅ [WEBHOOK-OUT] Notificação de REJEIÇÃO (ID: {}) confirmada (Status: {}).",
                                brokerOrderId,
                                response.getStatusCode()
                        ),
                        error -> log.error(
                                "❌ Falha ao enviar notificação de REJEIÇÃO para a ordem {}: {}",
                                brokerOrderId,
                                error.getMessage()
                        )
                );
    }

    /**
     * 🚨 NOVO MÉTODO CRÍTICO: Envia o tick de preço da Ponte para o Principal.
     * @param symbol Símbolo do ativo.
     * @param price Preço em tempo real.
     */
    public void sendMarketTick(String symbol, BigDecimal price) {
        MarketTickDTO tick = new MarketTickDTO(symbol, price);
        log.debug("▶️  [WEBHOOK-OUT] Enviando Market Tick para {} (R${}) para o Principal.", symbol, price);

        webClient.post()
                .uri("/api/v1/callbacks/ibkr/market-tick") // Endpoint que o Principal precisa implementar
                .bodyValue(tick)
                .retrieve()
                .toBodilessEntity()
                .subscribe(
                        response -> log.trace("✅ [WEBHOOK-OUT] Market Tick para {} confirmado (Status: {}).", symbol, response.getStatusCode()),
                        error -> log.error("❌ Falha ao enviar Market Tick para {}: {}", symbol, error.getMessage())
                );
    }
}