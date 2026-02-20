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
    public OrderExecutionResult placeSellOrder(String symbol, long quantity, BigDecimal price, String action, String reason) {
        synchronized (orderQueueLock) {
            try {
                // 🚨 VALIDAÇÃO DO PREÇO (O QUE ESTAVA DANDO ERRO)
                if (price == null || price.signum() <= 0) {
                    log.error("💥 [PONTE] Falha: Venda de {} requer preço válido (LMT/MKT-Prot).", symbol);
                    return new OrderExecutionResult(false, "Ordem LMT requer preço válido.");
                }

                if (quantity <= 0) return new OrderExecutionResult(false, "Qtd inválida.");

                log.info("🔥 [PONTE IBKR | EXEC] Enviando VENDA de {} @ {} para restaurar liquidez.", symbol, price);

                // --- Aqui você chamaria o EClient da IBKR passando o price ---
                Thread.sleep(50);

                OrderExecutionResult result = new OrderExecutionResult(true, "Ordem enviada.");
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
    public OrderExecutionResult placeBuyOrder(String symbol, long quantity, BigDecimal price, String action, String reason) {
        synchronized (orderQueueLock) {
            try {
                // 🚨 VALIDAÇÃO DO PREÇO
                if (price == null || price.signum() <= 0) {
                    return new OrderExecutionResult(false, "Ordem LMT requer preço válido.");
                }

                if (quantity <= 0) return new OrderExecutionResult(false, "Qtd inválida.");

                if (!isExcessLiquiditySufficient()) {
                    log.error("🛑 [VETO COMPRA] EL insuficiente (R$ {}).", portfolioService.getExcessLiquidity());
                    return new OrderExecutionResult(false, "Liquidez insuficiente.");
                }

                log.info("🚀 [PONTE IBKR | EXEC] Enviando COMPRA de {} @ {}.", symbol, price);
                Thread.sleep(50);

                OrderExecutionResult result = new OrderExecutionResult(true, "Ordem enviada.");
                result.setOrderId(nextOrderId++);
                return result;
            } catch (Exception e) {
                return new OrderExecutionResult(false, e.getMessage());
            }
        }
    }

    private boolean isExcessLiquiditySufficient() {
        // SSOT da Ponte
        return portfolioService.getExcessLiquidity().signum() > 0;
    }
}