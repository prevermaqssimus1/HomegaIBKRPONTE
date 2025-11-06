// Local: com.example.homegaibkrponte.connector.exception.OrdemFalhouException.java

package com.example.homegaibkrponte.exception;

// Esta é a exceção genérica para falhas na Ponte (excluindo falhas de Margem 201).

public class OrdemFalhouException extends RuntimeException {

    public OrdemFalhouException(String message) {
        super(message);
    }

    public OrdemFalhouException(String message, Throwable cause) {
        super(message, cause);
    }
}