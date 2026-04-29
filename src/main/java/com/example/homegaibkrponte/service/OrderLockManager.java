package com.example.homegaibkrponte.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class OrderLockManager {

    // Mantém o símbolo trancado por 5 segundos após um despacho de ejeção
    private final ConcurrentHashMap<String, Long> activeLocks = new ConcurrentHashMap<>();
    private static final long LOCK_DURATION_MS = 5000;

    public boolean isLocked(String symbol) {
        Long lockTime = activeLocks.get(symbol.toUpperCase());
        if (lockTime == null) return false;

        if (System.currentTimeMillis() - lockTime > LOCK_DURATION_MS) {
            activeLocks.remove(symbol.toUpperCase());
            return false;
        }
        return true;
    }

    public void lock(String symbol) {
        log.warn("🔐 [ORDER-LOCK] Bloqueando novas ejeções para {} por {}ms.", symbol, LOCK_DURATION_MS);
        activeLocks.put(symbol.toUpperCase(), System.currentTimeMillis());
    }
}