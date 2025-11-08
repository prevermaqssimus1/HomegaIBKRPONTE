package com.example.homegaibkrponte.service.execution;

import com.example.homegaibkrponte.model.Order; // ✅ CORREÇÃO: Importando do modelo da própria Ponte (Shared Model)
import com.example.homegaibkrponte.client.IBKRConnector;
import com.example.homegaibkrponte.model.OrderExecutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * SERVIÇO DA PONTE: Processa a fila de ordens para execução final.
 * A SINERGIA é garantida: A Ponte usa o modelo 'Order' que ela mesma hospeda.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IBKROrderExecutionService {

    private final BlockingQueue<Order> orderQueue;
    private final IBKRConnector ibkrConnector;

    @Async
    public void startOrderProcessing() {
        log.info("📢 [PONTE IBKR | EXECUTOR] Iniciando consumidor de fila de ordens...");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Consome a fila (Controle de Concorrência)
                Order order = orderQueue.poll(1, TimeUnit.SECONDS);

                if (order != null) {
                    processOrder(order);
                }
            } catch (InterruptedException e) {
                // TRY-CATCH para rastrear
                log.error("❌ [PONTE IBKR | ERRO CRÍTICO] Thread do executor interrompida. Encerrando o processamento de ordens.");
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("❌ [PONTE IBKR | ERRO INESPERADO] Falha no processamento de ordem: {}", e.getMessage(), e);
            }
        }
    }

    private void processOrder(Order order) {
        long finalQuantityToSend;

        // 🚨 CONVERSÃO DIMENSIONAL FINAL NA PONTE
        try {
            // Usa longValueExact: O Principal já arredondou (setScale(0)), a Ponte só faz a conversão final.
            finalQuantityToSend = order.quantity().longValueExact();
        } catch (ArithmeticException e) {
            log.error("❌ [PONTE IBKR | ERRO DIMENSIONAL] FALHA CRÍTICA: Quantidade {} não é um inteiro exato. Abortando execução.",
                    order.quantity().toPlainString());
            return;
        }

        // Execução na API Nativa (Bridge)
        try {
            log.warn("🔥 [PONTE IBKR | EXECUÇÃO] Enviando {} {} {} (Qtd Inteira: {}) para API nativa...",
                    order.side(), finalQuantityToSend, order.symbol(), finalQuantityToSend);

            // ✅ SINERGIA TOTAL: Chamada ao conector da IBKR com a correção dimensional final
            ibkrConnector.placeOrder(
                    order.symbol(),
                    finalQuantityToSend,
                    order.side().name(),
                    order.type().name()
            );
        } catch (Exception e) {
            log.error("❌ [PONTE IBKR | ERRO DE CONEXÃO] Falha ao enviar ordem {}: {}", order.clientOrderId(), e.getMessage(), e);
        }
    }
}