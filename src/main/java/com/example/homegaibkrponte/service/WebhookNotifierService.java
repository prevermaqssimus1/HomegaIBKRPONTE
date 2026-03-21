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
 * Central de Notificações para o sistema Principal.
 * Ajustado para suportar alertas de liquidez e telemetria Japão (.T).
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

    private final Retry retrySpec = Retry.backoff(3, Duration.ofSeconds(2));

    public WebhookNotifierService(
            @Value("${homega.app.webhook.base-url:http://127.0.0.1:8080}") String baseUrl
    ) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();
        log.info("🔔 [PONTE] Notificador configurado para: {}", baseUrl);
    }

    /**
     * ✅ RESOLVE ERRO 1: Notifica reduções preventivas (What-If).
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
                .subscribe(
                        res -> log.info("✅ [AMC] Telemetria de redução enviada: {}", symbol),
                        err -> log.error("❌ [AMC] Falha ao enviar telemetria.")
                );
    }

    /**
     * ✅ RESOLVE ERRO 2 e 3: Alertas de Liquidez (Warning).
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
                        res -> log.info("✅ [ALERTA] {} enviado ao Principal.", level),
                        err -> log.trace("Falha silenciada no alerta de liquidez.")
                );
    }

    /**
     * 🎌 ENVIO DE TICK REAL-TIME EVOLUÍDO (Oráculo v3.0)
     * Agora transporta bid, ask e volume para análise de microestrutura.
     */
    public void sendMarketTick(String symbol, BigDecimal price, BigDecimal bid, BigDecimal ask, Long size) {
        // 🛡️ Validação de sanidade para evitar nulidade no Ring Buffer do Principal
        BigDecimal validBid = (bid != null) ? bid : price;
        BigDecimal validAsk = (ask != null) ? ask : price;
        Long validSize = (size != null) ? size : 0L;
        Long timestamp = System.currentTimeMillis();

        // Criamos o DTO completo com o novo contrato
        MarketTickDTO tick = new MarketTickDTO(
                symbol,
                price,
                validBid,
                validAsk,
                validSize,
                timestamp
        );

        this.webClient.post()
                .uri(MARKET_TICK_URI) // Rota: /api/bridge/data/tick
                .bodyValue(tick)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> {
                    log.trace("⚠️ [TICK-DROP] Falha na entrega para {}. Status: {}", symbol, response.statusCode());
                    return Mono.empty();
                })
                .toBodilessEntity()
                .timeout(Duration.ofMillis(300)) // 🛡️ ROTA SNIPER: Latência reduzida para 300ms
                .subscribe(
                        success -> {}, // Sucesso silencioso para não poluir logs de alta frequência
                        err -> log.trace("Tick de {} dropado por timeout ou rede.", symbol)
                );
    }

    /**
     * Notifica rejeições (Trata erro 162 de IP).
     */
    public void sendOrderRejection(long orderId, int errorCode, String reason) {
        OrderRejectionDto rejection = new OrderRejectionDto(orderId, errorCode, reason);
        this.webClient.post()
                .uri(REJECTION_URI)
                .bodyValue(rejection)
                .retrieve()
                .toBodilessEntity()
                .retryWhen(retrySpec)
                .subscribe();
    }

    /**
     * 🚀 NOVO MÉTODO SINERGIZADO: Notifica rejeições enviando o ClientID real (String).
     * Essencial para o Winston encontrar a reserva de capital e limpar o Buying Power.
     */
    public void sendOrderRejection(String clientOrderId, long brokerOrderId, int errorCode, String reason) {
        // Usa o novo construtor que mapeia a String original do Winston
        OrderRejectionDto rejection = new OrderRejectionDto(clientOrderId, brokerOrderId, errorCode, reason);
        dispararPostRejection(rejection);
    }

    /**
     * Helper privado para centralizar o envio e evitar repetição de lógica.
     */
    private void dispararPostRejection(OrderRejectionDto rejection) {
        this.webClient.post()
                .uri(REJECTION_URI)
                .bodyValue(rejection)
                .retrieve()
                .toBodilessEntity()
                .retryWhen(retrySpec)
                .subscribe(
                        success -> log.info("✅ [WEBHOOK-OUT] Rejeição entregue: {}", rejection.getClientOrderId()),
                        err -> log.error("❌ [WEBHOOK-OUT] Falha ao entregar rejeição: {}", err.getMessage())
                );
    }

    /**
     * Notifica execuções reais (FILL).
     */
    public void sendExecutionReport(ExecutionReportDto report) {
        this.webClient.post()
                .uri(EXECUTION_STATUS_URI)
                .bodyValue(report)
                .retrieve()
                .toBodilessEntity()
                .retryWhen(retrySpec)
                .subscribe();
    }
}