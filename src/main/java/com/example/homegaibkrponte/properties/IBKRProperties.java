package com.example.homegaibkrponte.properties;

import com.example.homegaibkrponte.config.properties.ApiKeysProperties;

/**
 * Record que armazena as propriedades de conexão da IBKR.
 */
public record IBKRProperties(
        String host,
        String username,
        String password,
        int port,
        int clientId
) {
    public IBKRProperties(ApiKeysProperties.Ibkr ibkrConfig) {
        this(
                ibkrConfig.getHost(),
                ibkrConfig.getUsername(),
                ibkrConfig.getPassword(),
                ibkrConfig.getPort(),
                ibkrConfig.getClientId()
        );
    }

    // ✅ NOVO MÉTODO (NECESSÁRIO para sinergia) - A conta deve ser buscada do Connector ou fixada.
    // Retorna o ClientId como String, caso algum serviço peça o AccountId mas apenas tenha o ClientId.
    public String accountId() {
        // ⚠️ ATENÇÃO: Retornar uma String vazia ou um ID FALSO PODE CAUSAR ERRO 201.
        // Já que você não o tem na config, usaremos o ClientId, mas o ideal é que seja o ID da conta.
        return String.valueOf(this.clientId);
    }
}