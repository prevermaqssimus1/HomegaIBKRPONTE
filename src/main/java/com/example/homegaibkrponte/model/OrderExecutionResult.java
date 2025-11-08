package com.example.homegaibkrponte.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Modelo de dados para representar o resultado da submissão de uma ordem (Compra ou Venda)
 * para a Ponte IBKR.
 *
 * É crucial para garantir a sinergia e a comunicação tipada entre o Principal e a Ponte.
 * O uso de getters/setters e construtores garante a coesão do modelo (Princípio da Coesão).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OrderExecutionResult {

    // 1. Status: Essencial para o rastreamento (Logs e Try-Catch)
    private boolean success;

    // 2. ID da Ordem: Essencial para a sinergia com a fila e o histórico da IBKR
    private long orderId;

    // 3. Mensagem de Erro/Sucesso: Essencial para logs explicativos e diagnóstico
    private String message;

    /**
     * Construtor auxiliar para resultados onde o Order ID não é imediatamente conhecido
     * (útil para retornos de falha antes de a ordem entrar na fila).
     */
    public OrderExecutionResult(boolean success, String message) {
        this.success = success;
        this.message = message;
        this.orderId = 0; // Inicializa com 0
    }

    /**
     * Verifica se a execução resultou em sucesso.
     * @return true se a ordem foi processada com sucesso.
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Retorna a mensagem de erro ou sucesso.
     * Essencial para o log de rastreamento.
     * @return Mensagem explicativa.
     */
    public String getErrorMessage() {
        return message;
    }
}