package com.example.homegaibkrponte.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component; // <--- ANOTAÇÃO ADICIONADA

/**
 * Configurações para a divisão de ordens e limites de liquidez,
 * priorizando o uso de frações e métricas relativas ao NLV.
 */
@Component // ✅ CORRIGIDO: O Spring agora reconhecerá e gerenciará esta classe como um Bean.
public class OrdensConfig {

    // 1. Limites de Fracionamento de Ordem
    private BigDecimal maxFracBPPerOrder = new BigDecimal("0.50"); // Ajustado para 50% para ser razoável (era 0.25)

    // ... (restante das variáveis de instância)
    private Duration gapBetweenOrders = Duration.ofSeconds(2);
    private int maxConcurrentOrders = 4;
    private final List<Long> retryBackoffSeconds = List.of(1L, 2L, 4L, 8L, 16L);
    private BigDecimal minBPBufferFrac = new BigDecimal("0.05");

    // 2. Limites de Risco e GDL
    private BigDecimal fracaoSafeMode = new BigDecimal("0.20");
    private BigDecimal minReserveMarginFrac = new BigDecimal("0.10");
    private BigDecimal fracaoMaxVendaGDL = new BigDecimal("0.10");
    private Duration cooldownGDLVenda = Duration.ofSeconds(3);

    // Getters and Setters (Completos para uso)

    public BigDecimal getMinReserveMarginFrac() { return minReserveMarginFrac; }
    public void setMinReserveMarginFrac(BigDecimal v) { this.minReserveMarginFrac = v; }

    public BigDecimal getFracaoSafeMode() { return fracaoSafeMode; }
    public void setFracaoSafeMode(BigDecimal v) { this.fracaoSafeMode = v; }

    public BigDecimal getFracaoMaxVendaGDL() { return fracaoMaxVendaGDL; }
    public void setFracaoMaxVendaGDL(BigDecimal v) { this.fracaoMaxVendaGDL = v; }

    public Duration getCooldownGDLVenda() { return cooldownGDLVenda; }
    public void setCooldownGDLVenda(Duration d) { this.cooldownGDLVenda = d; }

    public BigDecimal getMaxFracBPPerOrder() { return maxFracBPPerOrder; }
    public void setMaxFracBPPerOrder(BigDecimal v) { this.maxFracBPPerOrder = v; }

    public Duration getGapBetweenOrders() { return gapBetweenOrders; }
    public void setGapBetweenOrders(Duration d) { this.gapBetweenOrders = d; }

    public int getMaxConcurrentOrders() { return maxConcurrentOrders; }
    public void setMaxConcurrentOrders(int maxConcurrentOrders) { this.maxConcurrentOrders = maxConcurrentOrders; }

    public List<Long> getRetryBackoffSeconds() { return retryBackoffSeconds; }

    public BigDecimal getMinBPBufferFrac() { return minBPBufferFrac; }
    public void setMinBPBufferFrac(BigDecimal v) { this.minBPBufferFrac = v; }
}