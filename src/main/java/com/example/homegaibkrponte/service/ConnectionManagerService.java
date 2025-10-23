package com.example.homegaibkrponte.service;

import com.example.homegaibkrponte.connector.IBKRConnector;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * SRP: Orquestra e gerencia o ciclo de vida da conexão com o TWS/Gateway.
 * Garante que a conexão esteja sempre ativa, acionando a reconexão quando necessário.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ConnectionManagerService {

    private final IBKRConnector ibkrConnector;

    /**
     * Inicia a primeira tentativa de conexão no arranque da aplicação.
     */
    @PostConstruct
    public void initialConnect() {
        log.info("▶️ [GESTOR DE CONEXÃO] Iniciando primeira tentativa de conexão...");
        ibkrConnector.connect();
    }

    /**
     * Padrão Health Check: Verifica periodicamente o estado da conexão.
     * Se a conexão cair, aciona o mecanismo de reconexão resiliente.
     */
    @Scheduled(fixedRate = 15000, initialDelay = 20000) // Verifica a cada 15 segundos
    public void ensureConnection() {
        if (!ibkrConnector.isConnected()) {
            log.warn("🔴 [GESTOR DE CONEXÃO] Conexão inativa detectada. Acionando reconexão...");
            ibkrConnector.connect();
        } else {
            log.trace("🟢 [GESTOR DE CONEXÃO] Verificação de saúde: Conexão ativa.");
        }
    }
}
