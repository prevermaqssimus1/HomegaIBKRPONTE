package com.example.homegaibkrponte.config;

import com.example.homegaibkrponte.config.properties.ApiKeysProperties;
import com.example.homegaibkrponte.properties.IBKRProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ApiKeysProperties.class)
public class ConnectorConfig {

    // Cria o Bean IBKRProperties que o IBKRConnector está esperando no construtor
    @Bean
    public IBKRProperties ibkrProperties(ApiKeysProperties apiKeysProperties) {
        ApiKeysProperties.Ibkr config = apiKeysProperties.getIbkr();

        // Retorna o Record IBKRProperties
        return new IBKRProperties(
                config.getHost(),
                config.getUsername(),
                config.getPassword(),
                config.getPort(),
                config.getClientId()
        );
    }
}