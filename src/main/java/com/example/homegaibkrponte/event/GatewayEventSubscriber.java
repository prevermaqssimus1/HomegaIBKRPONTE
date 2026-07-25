package com.example.homegaibkrponte.event;

import com.example.homegaibkrponte.client.IBKRGWClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Subscriber reativo responsável por interceptar falhas de socket na ponte
 * e acionar instantaneamente a recuperação do gateway.
 */
@Component
public class GatewayEventSubscriber {

    private static final Logger log = LoggerFactory.getLogger(GatewayEventSubscriber.class);

    private final IBKRGWClient ibkrGWClient;

    public GatewayEventSubscriber(IBKRGWClient ibkrGWClient) {
        this.ibkrGWClient = ibkrGWClient;
    }

    @EventListener
    public void handleGatewayDisconnectionEvent(GatewayConnectionFailedEvent event) {
        log.error("[AUTO-RECOVERY] Queda de socket detectada na ponte: {}. Acionando handshake corretivo imediato.", event.reason());

        try {
            // Chamará o método de reconexão que ajustaremos no Passo 2 dentro do IBKRGWClient
            // ibkrGWClient.reconnectAndHandshake();
        } catch (Exception e) {
            log.error("[FATAL] Erro crítico ao tentar reestabelecer o canal de socket de forma reativa na ponte.", e);
        }
    }
}