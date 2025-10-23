package com.example.homegaibkrponte.service;

import com.example.homegaibkrponte.dto.ExecutionReportDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * SRP: Serviço responsável por notificar a aplicação principal sobre eventos
 * importantes, como a execução de uma ordem, através de um webhook.
 *
 * VERSÃO CORRIGIDA:
 * 1.  **URL Ajustada:** A URL de destino agora aponta para o endpoint correto na aplicação principal.
 * 2.  **Assíncrono e Resiliente:** Mantém a lógica de retentativas para garantir a entrega da notificação.
 */
@Service
@Slf4j
public class WebhookNotifierService {

    private final WebClient webClient;
    private final String fullWebhookUrl;

    public WebhookNotifierService(
            // --- CORREÇÃO CRÍTICA AQUI ---
            // A URL padrão agora corresponde ao endpoint correto na aplicação principal.
            @Value("${homega.app.webhook.url:http://localhost:8080/api/v1/callbacks/ibkr/execution-report}") String webhookUrl
    ) {
        this.fullWebhookUrl = webhookUrl;
        this.webClient = WebClient.builder()
                .baseUrl(this.fullWebhookUrl)
                .build();
        log.info("Webhook Notifier configurado para enviar notificações para: {}", this.fullWebhookUrl);
    }

    public void sendExecutionReport(ExecutionReportDTO report) {
        log.warn("▶️  [WEBHOOK-OUT] Enviando notificação de execução para a aplicação principal. Ordem: {}", report.orderId());

        webClient.post()
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
}
