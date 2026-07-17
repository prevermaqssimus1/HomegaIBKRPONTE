package com.example.homegaibkrponte.rest;

import com.example.homegaibkrponte.connector.IBKRConnector;
import com.example.homegaibkrponte.dto.AccountStateDTO;
import com.example.homegaibkrponte.monitoring.LivePortfolioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 🚨 CONTROLLER DA PONTE: Expõe métricas críticas de liquidez para o sistema PRINCIPAL.
 * Inclui endpoints de Auditoria e Health Check Detalhado.
 * Utiliza LivePortfolioService como Fonte Única de Verdade (SSOT) para dados de cache.
 * Endpoints:
 * - /audit: Compara EL antes e depois de um refresh forçado para checar a sincronização.
 * - /health-detailed: Fornece o status completo e recomendações.
 * - /excess-robust: Retorna o EL com mecanismo de retry com backoff exponencial.
 */
@RestController
@RequestMapping("/api/liquidity")
@Slf4j
@RequiredArgsConstructor
public class LiquidityController {

    // Dependências injetadas via @RequiredArgsConstructor
    private final LivePortfolioService livePortfolioService;
    private final IBKRConnector ibkrConnector; // Para forçar snapshots e comunicação com a IBKR

    // --- 1. Endpoint de Auditoria de Liquidez (Cache vs. Fresco) ---
    /**
     * Realiza uma auditoria de sincronização.
     * Compara o Excess Liquidity (EL) do cache ANTES e DEPOIS de forçar uma atualização da IBKR.
     * Útil para diagnosticar a origem de dados 'stale' (desatualizados) ou dessincronizados.
     *
     * @return Map com os valores de EL e o status da discrepância.
     */
    @GetMapping("/audit")
    public ResponseEntity<Map<String, Object>> auditLiquidity() {
        Map<String, Object> audit = new HashMap<>();

        try {
            // 1. Obter valor do cache ANTES de forçar o refresh (Valor atual da Ponte)
            BigDecimal bridgeEL = livePortfolioService.getExcessLiquidity();

            // 2. Força busca direta da IBKR (dispara callbacks que atualizam o cache da Ponte)
            log.info("➡️ [PONTE | AUDIT] Forçando Account Summary Snapshot para obter dados frescos da IBKR.");
            int snapshotReqId = ibkrConnector.requestAccountSummarySnapshot();

            // 3. Aguarda resposta (Simula o tempo de latência do callback TWS)
            Thread.sleep(3000); // Aguarda 3 segundos

            // 4. Obter valor do cache DEPOIS do possível refresh (Novo valor da Ponte)
            BigDecimal freshEL = livePortfolioService.getExcessLiquidity();

            // 5. Montar resultado
            audit.put("bridgeCacheEL", bridgeEL.toPlainString());
            audit.put("freshIBKREL", freshEL.toPlainString());
            audit.put("snapshotRequestId", snapshotReqId);
            audit.put("timestamp", LocalDateTime.now());
            audit.put("discrepancy", !bridgeEL.equals(freshEL));
            audit.put("discrepancyAmount", freshEL.subtract(bridgeEL).abs().toPlainString());

            log.warn("🔍 [PONTE | AUDIT] EL Cache (Antes): R$ {}, EL Fresh (Depois): R$ {}, Discrepância: {}",
                    bridgeEL.toPlainString(), freshEL.toPlainString(), !bridgeEL.equals(freshEL));

            return ResponseEntity.ok(audit);

        } catch (Exception e) {
            log.error("❌ Falha crítica na auditoria de liquidez", e);
            // Em caso de falha na auditoria, retornar erro 500
            return ResponseEntity.status(500).build();
        }
    }

    // --- 2. Health Check Detalhado com Validação de Fonte ---
    /**
     * Fornece um status detalhado de liquidez, incluindo validação de frescor e recomendação.
     *
     * @return Map com métricas de liquidez e status de saúde.
     */
    @GetMapping("/health-detailed")
    public ResponseEntity<Map<String, Object>> getDetailedLiquidityHealth() {
        Map<String, Object> health = new HashMap<>();

        try {
            // 1. Obter valores do cache da Ponte
            BigDecimal currentEL = livePortfolioService.getExcessLiquidity();
            // A Liquidez (Buying Power) é obtida do mesmo SSOT do EL
            BigDecimal buyingPower = livePortfolioService.getCurrentBuyingPower();

            // 2. Força atualização para comparação
            log.info("➡️ [PONTE | HEALTH] Forçando Account Summary Snapshot para comparação de frescor.");
            ibkrConnector.requestAccountSummarySnapshot();
            Thread.sleep(3000); // Aguarda o callback

            // 3. Obtém o valor atualizado após o callback
            BigDecimal refreshedEL = livePortfolioService.getExcessLiquidity();

            // 4. Monta o status
            health.put("currentExcessLiquidity", currentEL.toPlainString());
            health.put("refreshedExcessLiquidity", refreshedEL.toPlainString());
            health.put("buyingPower", buyingPower.toPlainString());
            health.put("lastUpdate", LocalDateTime.now());
            // Critério: EL <= 0 (risco de liquidação)
            health.put("isCritical", currentEL.compareTo(BigDecimal.ZERO) <= 0);
            // Data Stale: O valor no cache mudou após o refresh forçado, indicando que o valor anterior estava desatualizado
            health.put("dataStale", !currentEL.equals(refreshedEL));
            health.put("recommendation", generateRecommendation(currentEL, refreshedEL));

            log.warn("🏥 [PONTE | HEALTH-DETAILED] EL: R$ {} | EL-Refresh: R$ {} | Crítico: {} | Desatualizado: {}",
                    currentEL.toPlainString(), refreshedEL.toPlainString(),
                    currentEL.compareTo(BigDecimal.ZERO) <= 0,
                    !currentEL.equals(refreshedEL));

            return ResponseEntity.ok(health);

        } catch (Exception e) {
            log.error("❌ Falha no health check detalhado", e);
            return ResponseEntity.status(500).build();
        }
    }

    // --- Método de Suporte para Recomendações ---
    private String generateRecommendation(BigDecimal currentEL, BigDecimal refreshedEL) {
        if (currentEL.compareTo(BigDecimal.ZERO) <= 0 && refreshedEL.compareTo(BigDecimal.ZERO) <= 0) {
            return "DEPÓSITO URGENTE NECESSÁRIO - Excess Liquidity (EL) real está zerado ou negativo na IBKR e na Ponte. Risco de liquidação.";
        } else if (currentEL.compareTo(BigDecimal.ZERO) <= 0 && refreshedEL.compareTo(BigDecimal.ZERO) > 0) {
            return "ALERTA DE CACHE DESATUALIZADO - O EL da Ponte estava zerado, mas foi recuperado no refresh. Forçar sincronização para restaurar valor antes de tomar decisões.";
        } else if (currentEL.compareTo(new BigDecimal("1000")) < 0) {
            // O valor de R$ 1000 é um exemplo, pode ser ajustado com base na sua política de risco
            return "ALERTA PRÉ-CRÍTICO - O EL está baixo (abaixo de R$ 1000,00). Considerar depósito preventivo para manter margem de segurança.";
        } else {
            return "SAUDÁVEL - Excess Liquidity está adequado. Monitorar continuamente.";
        }
    }

    // --- 3. Mecanismo de Retry com Backoff Exponencial (Para uso do Principal) ---
    /**
     * Endpoint primário de liquidez para o sistema Principal.
     * Se o valor do cache (Ponte) for zero, ele tentará forçar a atualização na IBKR
     * por um número de vezes com delay crescente (Backoff Exponencial).
     *
     * @return O valor mais recente e confiável de Excess Liquidity.
     */
    @GetMapping("/excess-robust")
    public ResponseEntity<BigDecimal> getExcessLiquidityRobust() {
        int maxRetries = 3;
        long initialDelayMs = 2000; // Começa com 2 segundos (2000ms)

        try {
            BigDecimal excessLiquidity = livePortfolioService.getExcessLiquidity();

            // Só inicia o retry se o EL estiver zero (o estado de falha crítica)
            if (excessLiquidity.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("🔄 [PONTE | ROBUSTO] EL zero detectado. Iniciando retry com backoff (Max {} tentativas)...", maxRetries);

                for (int attempt = 1; attempt <= maxRetries; attempt++) {
                    // Cálculo do delay exponencial: 2000 * 2^(attempt - 1) -> 2s, 4s, 8s
                    long delay = initialDelayMs * (long) Math.pow(2, attempt - 1);
                    log.info("🔄 [PONTE | ROBUSTO] Tentativa {}/{} - Delay: {}ms", attempt, maxRetries, delay);

                    // 1. Força o refresh da IBKR (Solicita um Account Summary Snapshot)
                    ibkrConnector.requestAccountSummarySnapshot();

                    // 2. Aguarda o delay para dar tempo ao callback do TWS (IBKR)
                    Thread.sleep(delay);

                    // 3. Lê o valor atualizado do cache (Ponte)
                    excessLiquidity = livePortfolioService.getExcessLiquidity();

                    if (excessLiquidity.compareTo(BigDecimal.ZERO) > 0) {
                        log.info("✅ [PONTE | ROBUSTO] EL recuperado na tentativa {}: R$ {}", attempt, excessLiquidity.toPlainString());
                        break;
                    }

                    if (attempt == maxRetries) {
                        log.error("❌ [PONTE | ROBUSTO] EL permanece zero após {} tentativas. Falha ao obter liquidez real. REQUER DEPÓSITO OU CHECAGEM MANUAL.", maxRetries);
                    }
                }
            }

            log.info("⬅️ [PONTE | ROBUSTO] Retornando EL final: R$ {}", excessLiquidity.toPlainString());
            return ResponseEntity.ok(excessLiquidity);

        } catch (Exception e) {
            log.error("❌ ERRO CRÍTICO no fluxo robusto de EL", e);
            // Retornar ZERO em caso de falha de serviço é o comportamento mais seguro
            // para forçar o VETO/Emergency Rescue Mode no sistema Principal.
            return ResponseEntity.status(500).body(BigDecimal.ZERO);
        }
    }

    /**
     * ✅ NOVO ENDPOINT: Estado Completo (SSOT)
     * Fornece ao sistema Principal a visão atômica de todos os parâmetros de risco.
     */
    @GetMapping("/account/state")
    public ResponseEntity<AccountStateDTO> getAccountState() {
        try {
            // Construção do DTO com os dados do seu SSOT interno (LivePortfolioService)
            AccountStateDTO state = AccountStateDTO.builder()
                    .netLiquidation(livePortfolioService.getNetLiquidation())
                    .cashBalance(livePortfolioService.getCashBalance())
                    .buyingPower(livePortfolioService.getCurrentBuyingPower())
                    .excessLiquidity(livePortfolioService.getExcessLiquidity())
                        .initMarginReq(livePortfolioService.getInitMarginReq())
                        .maintainMarginReq(livePortfolioService.getMaintainMarginReq()) // <--- A MENSAGEM QUE FALTAVA
                    .availableFunds(livePortfolioService.getAvailableFunds())
                    .currency("BRL")
                    .timestamp(java.time.Instant.now())
                    .build();

            return ResponseEntity.ok(state);
        } catch (Exception e) {
            log.error("❌ Falha ao montar AccountStateDTO completo", e);
            return ResponseEntity.status(500).build();
        }
    }
}