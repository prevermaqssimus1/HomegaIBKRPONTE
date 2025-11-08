package com.example.homegaibkrponte.client;

import com.example.homegaibkrponte.model.OrderExecutionResult;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLASSE DA PONTE (Bridge): Cliente de Comunicação com o Gateway da IBKR.
 * É a ÚNICA classe responsável por enviar ordens à corretora.
 * Garante a serialização e o controle de concorrência da fila de ordens.
 * Implementa o Princípio da Responsabilidade Única (SRP - SOLID).
 */
@Service
public class IBKRGWClient {

    // Logs explicativos para rastrear o que acontece
    private static final Logger log = LoggerFactory.getLogger(IBKRGWClient.class);

    // Variável para garantir a lógica de serialização e controle de concorrência (Fila de Order)
    private final Object orderQueueLock = new Object();
    private long nextOrderId = 1;

    /**
     * Implementa a lógica de envio de ordem de VENDA (SELL) para a Corretora.
     * Deve ser chamada pelo Principal (PositionExitManager) para Stop Loss ou Take Profit.
     * * @param symbol O ticker do ativo.
     * @param quantity A quantidade do ativo. CRÍTICO: DEVE SER LONG (INTEIRO).
     * @param action A ação da ordem (SELL).
     * @param reason O motivo da ordem.
     * @return O resultado da execução da ordem.
     */
    public OrderExecutionResult placeSellOrder(String symbol, long quantity, String action, String reason) {
        // Garantindo a Sinergia e Concorrência: A Fila de Ordem
        synchronized (orderQueueLock) {
            log.info("📢 [PONTE IBKR | FILA] Ordem de VENDA {} para {} (Qtd: {}) ENQUEUE. ID Interno: {}",
                    action, symbol, quantity, nextOrderId);

            // TRY-CATCH para rastrear o que acontece no código
            try {
                // VERIFICAÇÃO DE SEGURANÇA CONTRA O ERRO DIMENSIONAL DE LIQUIDEZ
                if (quantity <= 0) {
                    log.error("❌ [PONTE IBKR | ERRO DIMENSIONAL] Ordem rejeitada: Quantidade ({}) é inválida. A Ponte CANCELA para não prejudicar o que já existe.", quantity);
                    return new OrderExecutionResult(false, "Quantidade dimensional inválida (zero ou negativa).");
                }

                // Simulação do envio real da ordem via socket ou API IBKR
                // Neste ponto, a Ponte garante que a ordem vai ser processada.
                Thread.sleep(50);

                log.info("🔥 [PONTE IBKR | EXEC] Ordem {} para {} enviada à corretora. ID IBKR: {}", action, symbol, nextOrderId);

                // Simulação de sucesso
                OrderExecutionResult result = new OrderExecutionResult(true, "Ordem enviada e confirmada na fila da IBKR.");
                result.setOrderId(nextOrderId);

                nextOrderId++; // Incrementa para o próximo ID
                return result;

            } catch (Exception e) {
                // Não agir por conta própria. Apenas logar o erro e retornar a falha.
                log.error("❌ [PONTE IBKR | ERRO FATAL] Falha na comunicação ou Thread ao processar ordem para {}: {}", symbol, e.getMessage(), e);
                return new OrderExecutionResult(false, "Erro interno de concorrência/comunicação na Ponte: " + e.getMessage());
            }
        }
    }
}