package com.example.homegaibkrponte.event;

/**
 * Evento imutável disparado imediatamente no momento da falha ou queda de socket.
 */
public record GatewayConnectionFailedEvent(String reason, long timestamp) {
    public GatewayConnectionFailedEvent(String reason) {
        this(reason, System.currentTimeMillis());
    }
}