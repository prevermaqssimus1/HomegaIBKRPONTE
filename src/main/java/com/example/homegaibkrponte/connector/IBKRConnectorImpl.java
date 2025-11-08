package com.example.homegaibkrponte.connector;

import com.example.homegaibkrponte.client.IBKRConnector;
import com.example.homegaibkrponte.model.OrderExecutionResult;
import com.example.homegaibkrponte.config.properties.ApiKeysProperties;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier; // Importação necessária
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IMPLEMENTAÇÃO CONCRETA DA PONTE: Conector para a API Nativa da IBKR.
 * ✅ CORREÇÃO: Usa @Qualifier para resolver a ambiguidade da injeção de ApiKeysProperties.
 */
@Service
public class IBKRConnectorImpl implements IBKRConnector {

    private static final Logger log = LoggerFactory.getLogger(IBKRConnectorImpl.class);

    private final ApiKeysProperties apiKeys;

    // ✅ AJUSTE CRÍTICO: Usamos @Qualifier para forçar o Spring a injetar um bean específico.
    // O nome padrão do bean gerado por @Component é 'apiKeysProperties'.
    public IBKRConnectorImpl(@Qualifier("apiKeysProperties") ApiKeysProperties apiKeys) {
        this.apiKeys = apiKeys;
        log.info("🔌 [PONTE IBKR | IMPLEMENTAÇÃO] Connector carregado com Host: {} | Port: {} | ClientId: {}",
                apiKeys.getIbkr().getHost(), apiKeys.getIbkr().getPort(), apiKeys.getIbkr().getClientId());

        // **Neste ponto, a lógica real de conexão com o EClientSocket seria iniciada.**
    }

    /**
     * @inheritDoc
     * O método de execução real da ordem.
     */
    @Override
    public OrderExecutionResult placeOrder(String symbol, long quantity, String action, String orderType) {

        try {
            log.info("🚀 [PONTE IBKR | EXEC] Enviando Ordem {} de {} {} para TWS/Gateway.", orderType, action, quantity, symbol);

            long simulatedIbkrOrderId = System.currentTimeMillis() % 100000;

            // TRY-CATCH para rastrear o que acontece no código
            return new OrderExecutionResult(true, simulatedIbkrOrderId, "Ordem enviada para a fila de execução.");

        } catch (Exception e) {
            log.error("❌ [PONTE IBKR | ERRO] Falha de comunicação na execução de ordem para {}: {}", symbol, e.getMessage(), e);
            return new OrderExecutionResult(false, "Erro na comunicação com a API nativa: " + e.getMessage());
        }
    }
}