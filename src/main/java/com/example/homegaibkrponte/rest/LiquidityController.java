package com.example.homegaibkrponte.rest;

import com.example.homegaibkrponte.connector.IBKRConnector;
import com.example.homegaibkrponte.model.MarginMetricsDTO;
import com.example.homegaibkrponte.model.Order;
import com.example.homegaibkrponte.service.IBKRMarginService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * 🚨 CONTROLLER DA PONTE: Expõe métricas críticas de liquidez para o sistema PRINCIPAL.
 * O Principal deve chamar este endpoint para buscar o valor real-time da liquidez (ExcessLiquidity).
 */
@RestController
@RequestMapping("/api/liquidez")
@RequiredArgsConstructor
@Slf4j
public class LiquidityController {

    // A Ponte contém o cache e a lógica de comunicação com o TWS
    private final IBKRConnector ibkrConnector;
    private final IBKRMarginService marginService;

    /**
     * Retorna o valor atual da Liquidez em Excesso (Excess Liquidity).
     * O Principal deve usar este endpoint para validar a margem, conforme a regra de busca em tempo real.
     */
    @GetMapping("/excess")
    public ResponseEntity<BigDecimal> getExcessLiquidity() {
        // Aplica try-catch e logs explicativos (Obrigatório)
        try {
            log.info("➡️ [PONTE | API] Recebida requisição do Principal para obter Excess Liquidity.");

            // Tenta obter o valor do cache (atualizado pelos callbacks do Broker)
            BigDecimal excessLiquidity = ibkrConnector.getExcessLiquidityCache();

            // Verifica se o valor é zero e faz log de ALERTA CRÍTICO (Rastreamento)
            if (excessLiquidity.compareTo(BigDecimal.ZERO) <= 0) {
                log.error("❌🚨 [PONTE | ALERTA CRÍTICO] Excess Liquidity retornado como R$ {}. Gatilho de Resgate iminente no Principal!", excessLiquidity.toPlainString());
            } else {
                log.info("✅ [PONTE | DADO ENVIADO] Retornando Excess Liquidity: R$ {}", excessLiquidity.toPlainString());
            }

            // Retorna o valor (mesmo que seja zero)
            return ResponseEntity.ok(excessLiquidity);

        } catch (Exception e) {
            log.error("❌ ERRO CRÍTICO no LiquidityController ao obter Excess Liquidity. Rastreando.", e);
            // Retorna zero no caso de falha de serviço, forçando o resgate no Principal.
            return ResponseEntity.status(500).body(BigDecimal.ZERO);
        }
    }

    /**
     * Permite ao Principal forçar um Account Summary Snapshot (Para obter dados frescos)
     */
    @PostMapping("/snapshot")
    public ResponseEntity<Void> triggerAccountSummarySnapshot() {
        try {
            log.info("➡️ [PONTE | API] Recebida requisição do Principal para FORÇAR o Account Summary Snapshot.");
            ibkrConnector.requestAccountSummarySnapshot();
            return ResponseEntity.accepted().build();
        } catch (Exception e) {
            log.error("❌ [PONTE | ERRO] Falha ao forçar o Account Summary Snapshot. Rastreando.", e);
            return ResponseEntity.status(500).build();
        }
    }



    @PostMapping("/margin-check/{symbol}")
    public ResponseEntity<MarginMetricsDTO> getRealTimeMarginMetrics(
            @PathVariable String symbol,
            @RequestBody Order whatIfOrder) {

        log.info("➡️ [PONTE | API] Recebida requisição WhatIf para {} (Qtd: {}). Executando simulação de Margem...",
                symbol, whatIfOrder.quantity());

        try {
            // Chamada ao serviço interno da Ponte
            MarginMetricsDTO metrics = marginService.simulateWhatIf(whatIfOrder);

            log.info("✅ [PONTE | DADO ENVIADO] Margem WhatIf: {}", metrics.isMarginSafe());
            return ResponseEntity.ok(metrics);
        } catch (Exception e) {
            // Tratamento de erro na Ponte
            MarginMetricsDTO failedMetrics = new MarginMetricsDTO();
            failedMetrics.setMarginSafe(false);
            return ResponseEntity.internalServerError().body(failedMetrics);
        }
    }





}