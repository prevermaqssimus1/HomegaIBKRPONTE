package com.example.homegaibkrponte.client;

import com.example.homegaibkrponte.model.OrderExecutionResult;
import com.example.homegaibkrponte.monitoring.LivePortfolioService;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;

/**
 * 🌉 CLASSE DA PONTE: Ajustada para permitir Desalavancagem Crítica.
 * ✅ SUSTENTABILIDADE: Venda permitida mesmo com EL negativo para restaurar margem.
 */
@Service
@RequiredArgsConstructor
public class IBKRGWClient {

    private static final Logger log = LoggerFactory.getLogger(IBKRGWClient.class);
    private final Object orderQueueLock = new Object();
    private long nextOrderId = 1;
    private final LivePortfolioService portfolioService;

    // ====================================================================
    // 1. MÉTODO DE VENDA (LIBERA MARGEM)
    // ====================================================================
    public OrderExecutionResult placeSellOrder(String symbol, long quantity, String action, String reason) {
        synchronized (orderQueueLock) {
            try {
                if (quantity <= 0) return new OrderExecutionResult(false, "Qtd inválida.");

                // 🛡️ AJUSTE CRÍTICO: Removido o veto de Excess Liquidity para VENDAS.
                // Vender ativos reduz a Margem de Manutenção e ajuda a sair do negativo.

                log.info("🔥 [PONTE IBKR | EXEC] Enviando VENDA de {} para restaurar liquidez. ID: {}", symbol, nextOrderId);

                // Simulação da chamada de rede para a TWS/Gateway
                Thread.sleep(50);

                OrderExecutionResult result = new OrderExecutionResult(true, "Ordem de venda enviada.");
                result.setOrderId(nextOrderId++);
                return result;
            } catch (Exception e) {
                log.error("❌ [ERRO FATAL] Falha na venda de {}: {}", symbol, e.getMessage());
                return new OrderExecutionResult(false, e.getMessage());
            }
        }
    }

    // ====================================================================
    // 2. MÉTODO DE COMPRA (CONSOME MARGEM)
    // ====================================================================
    public OrderExecutionResult placeBuyOrder(String symbol, long quantity, String action, String reason) {
        synchronized (orderQueueLock) {
            try {
                if (quantity <= 0) return new OrderExecutionResult(false, "Qtd inválida.");

                // 🚨 VETO RIGOROSO: Compra proibida se liquidez for zero ou negativa.
                if (!isExcessLiquiditySufficient()) {
                    log.error("🛑 [VETO COMPRA] Excess Liquidity insuficiente (R$ {}). Operação bloqueada.",
                            portfolioService.getExcessLiquidity().toPlainString());
                    return new OrderExecutionResult(false, "Liquidez insuficiente para novas compras.");
                }

                log.info("🚀 [PONTE IBKR | EXEC] Enviando COMPRA de {}. ID: {}", symbol, nextOrderId);
                Thread.sleep(50);

                OrderExecutionResult result = new OrderExecutionResult(true, "Ordem de compra enviada.");
                result.setOrderId(nextOrderId++);
                return result;
            } catch (Exception e) {
                log.error("❌ [ERRO FATAL] Falha na compra de {}: {}", symbol, e.getMessage());
                return new OrderExecutionResult(false, e.getMessage());
            }
        }
    }

    private boolean isExcessLiquiditySufficient() {
        // SSOT da Ponte
        return portfolioService.getExcessLiquidity().signum() > 0;
    }
}