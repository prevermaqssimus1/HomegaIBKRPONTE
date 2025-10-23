package com.example.homegaibkrponte.config.properties;


import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * SRP: Centraliza todas as chaves de API e configurações de serviços externos.
 * Utiliza o mecanismo type-safe do Spring Boot para carregar do application.properties.
 */
@Component
@ConfigurationProperties(prefix = "api")
@Getter
@Setter
@Validated // Ativa a validação dos campos
public class ApiKeysProperties {

    private Ibkr ibkr = new Ibkr();

    /**
     * Propriedades para a API Web IBKR (modelo RESTful).
     * Usa username e password para autenticação de sessão.
     */
    @Getter
    @Setter
    public static class Ibkr {

        // Propriedades essenciais para autenticação Web

        private String host;


        private String username;


        private String password;

        // Campos Legados RESTAURADOS (sem @NotBlank) para suportar o construtor
        // do record IBKRProperties.
        private int port;
        private int clientId;
    }
}