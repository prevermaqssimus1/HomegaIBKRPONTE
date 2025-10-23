package com.example.homegaibkrponte.service;

import com.ib.client.EClientSocket;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gerencia a sequência de IDs de ordem de forma centralizada e thread-safe.
 * Utiliza o padrão Singleton (gerenciado pelo Spring).
 * SRP: Sua única responsabilidade é fornecer IDs de ordem válidos.
 */
@Service
public class OrderIdManager {

    // Utiliza AtomicInteger para garantir operações atômicas e seguras em ambiente multi-thread.
    private final AtomicInteger nextOrderId = new AtomicInteger(-1);

    /**
     * Inicializa ou atualiza o contador com o próximo ID válido fornecido pela API da IBKR.
     * Este método deve ser chamado assim que a conexão com o TWS é estabelecida.
     * @param validId O próximo ID de ordem válido.
     */
    public synchronized void initializeOrUpdate(int validId) {
        // Garante que o ID só seja definido uma vez ou atualizado se o novo for maior.
        if (this.nextOrderId.get() < validId) {
            this.nextOrderId.set(validId);
            System.out.println("✅ Contador de ID de Ordem inicializado com: " + validId);
        }
    }

    /**
     * Obtém o próximo ID de ordem disponível de forma atômica.
     * @return O próximo ID de ordem único.
     */
    public int getNextOrderId() {
        if (nextOrderId.get() == -1) {
            // Lança uma exceção se o serviço for usado antes da inicialização.
            throw new IllegalStateException("O OrderIdManager não foi inicializado com um ID válido da IBKR.");
        }
        // Incrementa e depois retorna o valor, garantindo que cada chamada receba um ID único.
        return this.nextOrderId.getAndIncrement();
    }
}