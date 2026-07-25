package com.example.homegaibkrponte.client;

import com.example.homegaibkrponte.model.OrderExecutionResult;
import com.example.homegaibkrponte.monitoring.LivePortfolioService;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.RequiredArgsConstructor;

/**
 * 🌉 CLASSE DA PONTE: Ajustada para permitir Desalavancagem Crítica e Auto-Recuperação Reativa.
 * ✅ SUSTENTABILIDADE: Venda permitida mesmo com EL negativo para restaurar margem[cite: 1].
 */
@Service
@RequiredArgsConstructor
public class IBKRGWClient {

    private static final Logger log = LoggerFactory.getLogger(IBKRGWClient.class);
    private final Object orderQueueLock = new Object();
    private long nextOrderId = 1;
    private final LivePortfolioService portfolioService;

    // Flag de controle de estado do socket interno da ponte
    private final AtomicBoolean isConnectedToGateway = new AtomicBoolean(true);

    // ====================================================================
    // 0. MÉTODO DE RECONEXÃO E HANDSHAKE REATIVO (PASSO 2)
    // ====================================================================
    public void reconnectAndHandshake() {
        synchronized (orderQueueLock) {
            log.warn("🔄 [PONTE | AUTO-RECOVERY] Iniciando protocolo de re-autenticação e reconexão de socket...");
            try {
                // 1. Simula a desconexão e limpeza de buffers corrompidos do EClient da IBKR
                isConnectedToGateway.set(false);

                // --- Aqui entraria a chamada real de desconexão/limpeza do socket da IBKR se necessário ---
                Thread.sleep(100);

                log.info("🔌 [PONTE | AUTO-RECOVERY] Reestabelecendo canal de socket com a IBKR...");
                // --- Aqui entraria a chamada real de reconexão do EClient (ex: client.eConnect(...)) ---
                Thread.sleep(200);

                // 2. Marca o canal como restabelecido com sucesso
                isConnectedToGateway.set(true);
                log.info("✅ [PONTE | AUTO-RECOVERY] Socket reconectado e handshake executado com sucesso.");

                // 3. Notificação explícita para o sistema principal (será integrada no Passo 3)
                // broadcastConnectionRestored();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("❌ [PONTE | ERRO] Thread de reconexão interrompida.", e);
            } catch (Exception e) {
                log.error("❌ [PONTE | ERRO FATAL] Falha durante a re-autenticação reativa: {}", e.getMessage(), e);
            }
        }
    }

    public boolean isConnected() {
        return isConnectedToGateway.get();
    }

    // ====================================================================
    // 1. MÉTODO DE VENDA (LIBERA MARGEM)
    // ====================================================================
    public OrderExecutionResult placeSellOrder(String symbol, long quantity, BigDecimal price, String action, String reason) {
        synchronized (orderQueueLock) {
            try {
                if (!isConnectedToGateway.get()) {
                    log.error("🛑 [VETO VENDA] Ponte desconectada do Gateway IBKR.");
                    return new OrderExecutionResult(false, "Gateway desconectado.");
                }

                if ((price == null || price.signum() <= 0) && !action.contains("MARKET")) {
                    log.error("💥 [PONTE] Falha: Ordem LMT de {} requer preço válido.", symbol);
                    return new OrderExecutionResult(false, "Preço inválido.");
                }

                if (quantity <= 0) return new OrderExecutionResult(false, "Qtd inválida.");

                log.info("🔥 [PONTE IBKR | EXEC] Enviando VENDA de {} @ {} para restaurar liquidez.", symbol, price);

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
    // 2. MÉTODO de COMPRA (CONSOME MARGEM)
    // ====================================================================
    public OrderExecutionResult placeBuyOrder(String symbol, long quantity, BigDecimal price, String action, String reason) {
        synchronized (orderQueueLock) {
            try {
                if (!isConnectedToGateway.get()) {
                    log.error("🛑 [VETO COMPRA] Ponte desconectada do Gateway IBKR.");
                    return new OrderExecutionResult(false, "Gateway desconectado.");
                }

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
        return portfolioService.getExcessLiquidity().signum() > 0;
    }
}