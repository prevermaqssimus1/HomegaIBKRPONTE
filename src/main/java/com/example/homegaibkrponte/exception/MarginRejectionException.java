package com.example.homegaibkrponte.exception;

import lombok.NoArgsConstructor;

/**
 * Exceção específica para rastrear falhas de ordens na Ponte IBKR
 * que foram rejeitadas por insuficiência de margem (código 201 ou pré-cheques).
 */
@NoArgsConstructor
public class MarginRejectionException extends RuntimeException {

    // Construtor padrão com mensagem
    public MarginRejectionException(String message) {
        super(message);
    }

    // Construtor com mensagem e causa
    public MarginRejectionException(String message, Throwable cause) {
        super(message, cause);
    }
}