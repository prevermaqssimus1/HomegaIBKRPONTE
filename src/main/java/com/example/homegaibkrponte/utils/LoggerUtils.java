package com.example.tradingplatform.application.util;

import org.slf4j.Logger;

/**
 * Utilitário para padronizar logs de erro, garantindo que o contexto da
 * classe (logger) e o método (contexto) sejam sempre registrados.
 */
public final class LoggerUtils {

    private LoggerUtils() {
        // Classe utilitária estática
    }

    /**
     * Registra uma mensagem de erro padronizada.
     *
     * @param logger O logger da classe chamadora.
     * @param context O contexto ou nome do método onde o erro ocorreu.
     * @param message A mensagem principal do erro.
     * @param e A exceção (Throwable) associada.
     */
    public static void logError(Logger logger, String context, String message, Throwable e) {
        logger.error("❌ [ERRO | {}] {}. Causa: {}", context, message, e.getMessage(), e);
    }
}