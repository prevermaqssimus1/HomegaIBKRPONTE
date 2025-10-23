package com.example.homegaibkrponte.service;

import com.example.homegaibkrponte.connector.IBKRConnector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountService {

    private final IBKRConnector connector;

    public String getConnectionStatus() {
        if (!connector.isConnected()) {
            connector.connect();
        }
        return connector.isConnected() ? "CONNECTED" : "DISCONNECTED";
    }

    public BigDecimal fetchAndGetBuyingPower() {
        // Garante que a conexão esteja ativa
        if (!"CONNECTED".equals(getConnectionStatus())) {
            log.error("❌ Gateway TWS offline. Falha ao obter saldo.");
            return BigDecimal.ZERO;
        }

        BigDecimal balance = connector.getBuyingPowerCache();

        // Se o saldo for ZERO, dispara o callback de atualização na IBKR
        if (BigDecimal.ZERO.compareTo(balance) == 0) {
            log.warn("⚠️ Saldo em cache é ZERO. Disparando reqAccountUpdates para forçar sincronização do TWS.");
            connector.getClient().reqAccountUpdates(true, connector.getManagedAccounts());
        }
        return balance;
    }
}