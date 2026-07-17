package com.example.homegaibkrponte.service;

import com.example.homegaibkrponte.connector.IBKRConnector;
import com.example.homegaibkrponte.monitoring.LivePortfolioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean; // Necessário para controle

/**
 * Serviço de monitoramento periódico de liquidez.
 * Utiliza o LivePortfolioService (a Fonte Única de Verdade da Ponte)
 * para checar os níveis de Excess Liquidity (EL) no cache e acionar
 * alertas ou um refresh de emergência em casos críticos.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LiquidityMonitorService {

    // Dependências injetadas via @RequiredArgsConstructor
    private final IBKRConnector ibkrConnector;
    private final LivePortfolioService livePortfolioService; // 🎯 Fonte Única de Verdade (SSOT)
    private final WebhookNotifierService notifier; // Assumido no ecossistema

    // 🛑 CONTROLE DE ESTADO: Garante que a espera só ocorra na primeira execução.
    private final AtomicBoolean isInitialCheckDone = new AtomicBoolean(false);


    // Limiares de Alerta. Ajustar conforme a política de risco.
    private static final BigDecimal CRITICAL_THRESHOLD = new BigDecimal("1000"); // Alerta de caixa
    private static final BigDecimal WARNING_THRESHOLD = new BigDecimal("10000"); // Alerta de baixo nível

    /**
     * Monitora a liquidez em intervalos fixos (a cada 5 minutos - 300000ms).
     * O tempo de execução da tarefa deve ser configurável via application.yml.
     */
    @Scheduled(fixedRate = 300000)
    public void monitorLiquidity() {
//        log.info("⏰ [MONITOR-AUTO] Iniciando checagem programada de liquidez...");
        try {
            // 🛑 AJUSTE CRÍTICO: Barreira de Sincronização na primeira execução (Fix para Race Condition)
            if (isInitialCheckDone.compareAndSet(false, true)) {
                log.warn("⏳ [MONITOR] Primeira checagem. Aguardando a sincronização crítica de Margem/Liquidez...");

                // Espera pelo Latch que é liberado no LivePortfolioService (Ponte)
                boolean ready = livePortfolioService.getCriticalMarginDataLatch().await(15, TimeUnit.SECONDS);

                if (!ready) {
                    log.error("❌ [MONITOR] TIMEOUT! Dados críticos de Margem/Liquidez não foram carregados após 15s. O monitoramento será iniciado, mas os dados podem estar incompletos.");
                    // Permite que o monitoramento continue (lendo R$ 0.00 se for o caso), mas registra o erro de timeout.
                } else {
                    log.info("✅ [MONITOR] Barreira de Margem/Liquidez liberada. Iniciando monitoramento normal.");
                }
            }

            // 🎯 Obter o EL do cache da Ponte (LivePortfolioService)
            BigDecimal currentEL = livePortfolioService.getExcessLiquidity();

            if (currentEL.compareTo(BigDecimal.ZERO) <= 0) {
                log.error("🚨 [MONITOR-AUTO] EL CRÍTICO: R$ {}. Acionando refresh de emergência.", currentEL.toPlainString());
                notifier.notifyCriticalLiquidity("DEPÓSITO URGENTE - Excess Liquidity zerado ou negativo na Ponte.");
                forceEmergencyRefresh();
            } else if (currentEL.compareTo(CRITICAL_THRESHOLD) <= 0) {
                log.warn("⚠️ [MONITOR-AUTO] EL PRÉ-CRÍTICO: R$ {}. Acima de zero, mas abaixo do limite de R$ {}.",
                        currentEL.toPlainString(), CRITICAL_THRESHOLD.toPlainString());
                notifier.notifyWarningLiquidity("Pré-Crítico - EL abaixo de R$ 1000. Considere aporte.");
            } else if (currentEL.compareTo(WARNING_THRESHOLD) <= 0) {
                log.info("🔔 [MONITOR-AUTO] EL BAIXO: R$ {}. Abaixo do limite de conforto de R$ {}.",
                        currentEL.toPlainString(), WARNING_THRESHOLD.toPlainString());
            } else {
//                log.info("✅ [MONITOR-AUTO] EL SAUDÁVEL: R$ {}.", currentEL.toPlainString());
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ [MONITOR-AUTO] Thread de monitoramento interrompida durante a espera.", e);
        } catch (Exception e) {
            log.error("❌ [MONITOR-AUTO] Falha no monitoramento automático de liquidez", e);
        }
    }

    /**
     * Força uma solicitação de Account Summary Snapshot para atualizar o cache da Ponte,
     * especialmente após detectar um EL crítico.
     */
    private void forceEmergencyRefresh() {
        try {
            log.warn("🔄 [MONITOR-AUTO] Forçando refresh de emergência (Snapshot IBKR)...");
            // Nota: O requestAccountSummarySnapshot() usa o reqId genérico, o que é aceitável aqui.
            ibkrConnector.requestAccountSummarySnapshot();

            // Espera o callback retornar. Este tempo deve ser ajustado para a latência real.
            Thread.sleep(3000);

            // Verifica o EL novamente após o refresh
            BigDecimal refreshedEL = livePortfolioService.getExcessLiquidity();

            if (refreshedEL.compareTo(BigDecimal.ZERO) > 0) {
                log.info("✅ [MONITOR-AUTO] EL recuperado para: R$ {} após refresh.", refreshedEL.toPlainString());
            } else {
                log.error("🚨 [MONITOR-AUTO] EL PERMANECE ZERO (R$ {}). Ação manual ou ativação do fluxo robusto (excess-robust) necessária.", refreshedEL.toPlainString());
            }
        } catch (Exception e) {
            log.error("❌ [MONITOR-AUTO] Falha no refresh de emergência", e);
        }
    }
}