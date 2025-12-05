package com.example.homegaibkrponte.service;
// Pacote: com.example.homegaibkrponte.service

import com.example.homegaibkrponte.config.OrdensConfig;
import com.example.homegaibkrponte.model.LiquidityMode;
import org.springframework.stereotype.Service; // <-- ESSA É A ANOTAÇÃO NECESSÁRIA

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service // ✅ CORRIGIDO: Indica ao Spring que esta classe é um serviço e deve ser injetada.
public class LiquidityManager {

    private final OrdensConfig config;

    public LiquidityManager(OrdensConfig config) {
        this.config = config;
    }

    /**
     * Avalia o modo operacional (SSOT de governança).
     */
    public LiquidityMode avaliar(
            BigDecimal bpDisponivel,
            BigDecimal nlv,
            BigDecimal custoEstimadoTotal,
            BigDecimal cash,
            BigDecimal reserveMarginFracAtual) {

        // ... (Lógica de avaliação omitida por brevidade, mas deve ser a sua implementação completa) ...
        if (reserveMarginFracAtual.compareTo(config.getMinReserveMarginFrac()) < 0) {
            System.out.printf("🚨 [LIQUIDITY_MANAGER] EMERGENCY! Margem %.2f%% abaixo do limite de %.2f%%.%n",
                    reserveMarginFracAtual.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP),
                    config.getMinReserveMarginFrac().multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP));
            return LiquidityMode.EMERGENCY;
        }

        if (custoEstimadoTotal.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal ratioBPvsCusto = bpDisponivel.divide(custoEstimadoTotal, 6, RoundingMode.HALF_UP);

            if (ratioBPvsCusto.compareTo(config.getFracaoSafeMode()) < 0) {
                System.out.printf("⚠️ [LIQUIDITY_MANAGER] SAFE_MODE. BP/Custo (%.2f%%) abaixo do limite de %.2f%%. GDL pode ser necessária.%n",
                        ratioBPvsCusto.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP),
                        config.getFracaoSafeMode().multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP));
                return LiquidityMode.SAFE_MODE;
            }
        }

        if (cash.signum() < 0) {
            System.out.printf("⚠️ [LIQUIDITY_MANAGER] RECOVERY. Cash negativo: %.2f. Necessário priorizar vendas para repor cash.%n", cash);
            return LiquidityMode.RECOVERY;
        }

        return LiquidityMode.HIGH_LIQUIDITY;
    }

    /**
     * Retorna o BP Máximo Usável para a próxima ordem.
     */
    public BigDecimal ajustarBPParaOperacao(BigDecimal bpDisponivel, LiquidityMode mode) {
        final BigDecimal EMERGENCY_FACTOR = new BigDecimal("0.05");
        final BigDecimal SAFE_FACTOR = new BigDecimal("0.15");
        final BigDecimal RECOVERY_FACTOR = new BigDecimal("0.10");

        return switch (mode) {
            case EMERGENCY -> bpDisponivel.multiply(EMERGENCY_FACTOR);
            case SAFE_MODE -> bpDisponivel.multiply(SAFE_FACTOR);
            case RECOVERY -> bpDisponivel.multiply(RECOVERY_FACTOR);
            case HIGH_LIQUIDITY -> bpDisponivel.multiply(config.getMaxFracBPPerOrder());
        };
    }
}