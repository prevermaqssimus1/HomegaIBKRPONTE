package com.example.homegaibkrponte.properties;

import com.example.homegaibkrponte.config.properties.ApiKeysProperties;

// O record deve ter seus parâmetros referenciados diretamente se for um record Java 14+
// Caso contrário, use uma classe normal com @Getter para garantir a geração.
public record IBKRProperties(
        String host,
        String username,
        String password,
        int port,
        int clientId
) {
    public IBKRProperties(ApiKeysProperties.Ibkr ibkrConfig) {
        this(
                // Chamadas diretas ao método getter do Lombok (ex: getHost())
                ibkrConfig.getHost(), // <-- Use o getter explícito
                ibkrConfig.getUsername(),
                ibkrConfig.getPassword(),
                ibkrConfig.getPort(),
                ibkrConfig.getClientId()
        );
    }
}