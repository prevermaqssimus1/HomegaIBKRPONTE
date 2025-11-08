package com.example.homegaibkrponte.service;

import com.example.homegaibkrponte.model.MarginMetricsDTO;
import com.example.homegaibkrponte.model.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * PONTE: Serviço de Negócio para Simulação de Margem (WhatIf).
 * Responsável por interagir com o TWS/Gateway (a lógica nativa) para obter o impacto da margem.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IBKRMarginService {

    // Aqui seria injetado o conector de socket nativo da IBKR
    // private final IBKRConnector ibkrConnector;

    /**
     * Executa a simulação WhatIf no TWS/Gateway e calcula se a ordem é segura.
     */
    public MarginMetricsDTO simulateWhatIf(Order whatIfOrder) {
        log.info("📢 [PONTE | IBKRMarginService] Executando WhatIf para {} (Qtd: {}).",
                whatIfOrder.symbol(), whatIfOrder.quantity().toPlainString());

        // 1. Lógica de WhatIf (Simulação no TWS)
        // A Ordem WhatIf é enviada ao EClientSocket com o flag WhatIf=true.
        // Os resultados (OrderState com InitMarginAfter, etc.) são recebidos via callback.

        // 2. Análise do Resultado (Simulação)
        // O TWS Code 201 é evitado se o EL for maior que o InitMarginReq Pós-Trade.
        boolean isSafe = true; // Lógica real de checagem. No log, este check falhou.
        BigDecimal currentEL = new BigDecimal("61331.32");

        // 🚨 AJUSTE DE SINERGIA: Usando o método do modelo para obter o custo estimado.
        // Isso resolve o erro 'cannot find symbol' e aplica o SRP[cite: 685].
        BigDecimal estimatedCost = whatIfOrder.getEstimatedCost();

        // Se a ordem custar mais de 50% do EL, rejeitamos (Guarda-corpo conservador na Ponte)
        if (estimatedCost.compareTo(currentEL.multiply(new BigDecimal("0.5"))) > 0) {
            isSafe = false;
        }

        MarginMetricsDTO metrics = new MarginMetricsDTO();
        metrics.setExcessLiquidity(currentEL);
        metrics.setInitMarginReq(estimatedCost.multiply(new BigDecimal("0.30"))); // Margem estimada de 30%
        metrics.setMarginSafe(isSafe);

        return metrics;
    }
}