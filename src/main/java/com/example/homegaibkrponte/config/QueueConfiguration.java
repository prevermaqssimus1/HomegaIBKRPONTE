package com.example.homegaibkrponte.config;

import com.example.homegaibkrponte.model.Order;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Configuration
public class QueueConfiguration {

    // Define uma capacidade segura para a fila, evitando esgotamento de memória
    private static final int ORDER_QUEUE_CAPACITY = 1000;

    /**
     * Define o bean da BlockingQueue<Order> que será compartilhado.
     * * SINERGIA: Esta é a "fila de Order" que garante a serialização e concorrência
     * das execuções (Stop Loss/Take Profit) entre o Principal (Coloca) e a Ponte (Consome).
     *
     * @return Uma BlockingQueue thread-safe com capacidade definida.
     */
    @Bean
    public BlockingQueue<Order> orderQueue() {
        // Usamos LinkedBlockingQueue, uma implementação thread-safe
        return new LinkedBlockingQueue<>(ORDER_QUEUE_CAPACITY);
    }
}