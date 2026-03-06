package com.example.homegaibkrponte.service;

import com.example.homegaibkrponte.properties.IBKRProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 🌉 PONTE | GESTÃO DE IDENTIFICADORES
 * Gerencia a sequência de IDs e a TRADUÇÃO entre Principal (ClientRef) e TWS (OrderId).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderIdManager {

    private final IBKRProperties ibkrProps;

    // Inicializado com -1 para forçar a sincronização via nextValidId da TWS
    private final AtomicInteger nextOrderId = new AtomicInteger(-1);

    // 🧠 MEMÓRIA DE TRADUÇÃO: Chave: ClientOrderId (String) -> Valor: IBKR OrderId (Integer)
    private final Map<String, Integer> idMapping = new ConcurrentHashMap<>();

    private final Map<Integer, String> reverseMapping = new ConcurrentHashMap<>();


    /**
     * Sincroniza o ID com Salto de Segurança.
     */
    public synchronized void initializeOrUpdate(int validId) {
        int safeId = validId + 2000;
        int current = this.nextOrderId.get();
        if (safeId > current) {
            this.nextOrderId.set(safeId);
            log.error("✅ [OrderIdManager] ID sincronizado com SALTO DE SEGURANÇA. Próximo ID: {}", safeId);
        }
    }

    /**
     * 📝 VINCULA IDs: Salva a relação entre o ID do Principal e o ID que a TWS gerou.
     */
    public void linkIds(String clientOrderId, int ibkrOrderId) {
        if (clientOrderId != null) {
            idMapping.put(clientOrderId, ibkrOrderId);
            reverseMapping.put(ibkrOrderId, clientOrderId); // 🎯 Salva o caminho de volta
            log.debug("🔗 [OrderIdManager] Vinculado: {} <-> {}", clientOrderId, ibkrOrderId);
        }
    }

    /**
     * 🔍 BUSCA ID: Recupera o ID numérico da IBKR para poder cancelar a ordem.
     */
    public Integer getIbkrOrderId(String clientOrderId) {
        return idMapping.get(clientOrderId);
    }

    /**
     * 🔍 BUSCA CLIENT ID: O método que a Ponte precisa para o estorno de saldo.
     * Recupera a String (ex: WNS-TSLA...) usando o número da IBKR.
     */
    public String getClientOrderId(int ibkrOrderId) {
        // Se encontrar a String original, retorna ela.
        // Se não encontrar, retorna o número como String para não quebrar o código.
        return reverseMapping.getOrDefault(ibkrOrderId, String.valueOf(ibkrOrderId));
    }


    public void removeMapping(String clientOrderId) {
        if (clientOrderId != null) {
            Integer ibkrId = idMapping.remove(clientOrderId);
            if (ibkrId != null) {
                reverseMapping.remove(ibkrId);
            }
        }
    }

    public int getNextOrderId() {
        int id = nextOrderId.get();
        if (id == -1) {
            log.error("🛑 [CRÍTICO] Tentativa de obter ID antes da sincronização.");
            throw new IllegalStateException("OrderIdManager não inicializado.");
        }
        return this.nextOrderId.getAndIncrement();
    }

    public int getCurrentId() { return nextOrderId.get(); }
    public int getClientId() { return ibkrProps.clientId(); }
    public String getAccountId() { return ibkrProps.accountId(); }
}