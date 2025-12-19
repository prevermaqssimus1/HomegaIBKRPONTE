package com.example.homegaibkrponte.service;

import com.example.homegaibkrponte.properties.IBKRProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 🌉 PONTE | GESTÃO DE IDENTIFICADORES
 * Gerencia a sequência de IDs de ordem garantindo unicidade e sinergia com a TWS.
 * Implementa Salto de Segurança para resolver Erro 103 (Duplicate Order ID). [cite: 334]
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderIdManager {

    private final IBKRProperties ibkrProps;

    // Inicializado com -1 para forçar a sincronização via nextValidId da TWS
    private final AtomicInteger nextOrderId = new AtomicInteger(-1);

    /**
     * 🚀 INICIALIZAÇÃO COM SALTO DE SEGURANÇA
     * Resolve o Erro 103 garantindo que o ID esteja sempre à frente do histórico da TWS. [cite: 334]
     */
    public synchronized void initializeOrUpdate(int validId) {
        // 🛡️ SINERGIA: Aplicamos um salto de 2000 unidades sobre o ID sugerido pela TWS. [cite: 325, 360]
        // Isso garante que ordens de sessões anteriores não causem conflito.
        int safeId = validId + 2000;

        int current = this.nextOrderId.get();
        if (safeId > current) {
            this.nextOrderId.set(safeId);
            log.error("✅ [OrderIdManager] ID sincronizado com SALTO DE SEGURANÇA. Próximo ID: {}", safeId);
        }
    }

    /**
     * ⚠️ SALTO FORÇADO DE EMERGÊNCIA
     * Use este método quando o erro 103 for detectado em tempo de execução.
     */
    public synchronized void forceIdJump() {
        int currentId = nextOrderId.get();
        if (currentId != -1) {
            int jumpedId = currentId + 1000;
            nextOrderId.set(jumpedId);
            log.error("🚀 [OrderIdManager | EMERGENCY] Salto forçado de 1000 unidades aplicado. Novo ID: {}", jumpedId);
        }
    }

    /**
     * Obtém o próximo ID de ordem disponível de forma atômica.
     */
    public int getNextOrderId() {
        int id = nextOrderId.get();
        if (id == -1) {
            log.error("🛑 [CRÍTICO] Tentativa de obter ID antes da sincronização com a TWS.");
            throw new IllegalStateException("OrderIdManager não inicializado.");
        }
        return this.nextOrderId.getAndIncrement();
    }

    /**
     * Retorna o ID atual sem incrementar (para monitoramento).
     */
    public int getCurrentId() {
        return nextOrderId.get();
    }

    public int getClientId() {
        return ibkrProps.clientId();
    }

    public String getAccountId() {
        // Retorna a conta DUN652604 [cite: 284]
        return ibkrProps.accountId();
    }
}