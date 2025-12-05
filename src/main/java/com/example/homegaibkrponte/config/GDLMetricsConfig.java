package com.example.homegaibkrponte.config;

// Exemplo de Configuração de Métricas (Spring Boot)
// Você adicionaria esta classe ao seu projeto, marcando-a com @Configuration
// e usando o MeterRegistry injetado.

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import jakarta.annotation.PostConstruct;

@Configuration
public class GDLMetricsConfig {

    private final MeterRegistry registry;
    private final AtomicLong currentBp = new AtomicLong(0);

    public GDLMetricsConfig(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void setupMetrics() {
        // 1. Contador de Vendas GDL
        Counter.builder("gdl_sales_total")
                .description("Contagem de ordens de venda geradas pela GDL")
                .register(registry);

        // 2. Total de BP Gerado
        Counter.builder("gdl_bp_generated")
                .description("Total de Poder de Compra (BP) gerado pela GDL (em moeda)")
                // .tags("currency", "BRL") // Adicione tags se necessário
                .register(registry);

        // 3. Contador de Confirmações de BP
        Counter.builder("gdl_confirmations")
                .description("Número de callbacks de BP recebidos após vendas GDL")
                .register(registry);

        // 4. Latência de Confirmação
        Timer.builder("gdl_latency_seconds")
                .description("Tempo entre envio da venda GDL e confirmação do BP pela Ponte")
                .register(registry);

        // 5. Gauge de BP Disponível (SSOT)
        Gauge.builder("gdl_bp_available", currentBp, AtomicLong::get)
                .description("BP disponível atual (SSOT da Ponte IBKR)")
                .baseUnit("BRL")
                .register(registry);
    }

    // Método para atualizar a métrica de BP (chamado pelo callback da Ponte)
    public void updateBpAvailable(BigDecimal newBp) {
        currentBp.set(newBp.longValue());
    }

    public void incrementSalesCounter(double amount) {
        registry.counter("gdl_sales_total").increment();
        registry.counter("gdl_bp_generated").increment(amount);
    }

    // Para Logs Estruturados:
    // log.info("[signalId={}] [action={}] ...", signalId, "GDL_ATIVADO");
    // Garanta que você está usando uma biblioteca como Logback e configurando o MDC (Mapped Diagnostic Context)
    // no thread principal para injetar o 'signalId' em todos os logs da transação.
}