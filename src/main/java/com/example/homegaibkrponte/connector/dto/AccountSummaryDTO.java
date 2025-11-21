package com.example.homegaibkrponte.connector.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Representa os dados resumidos da conta obtidos via API.
 * Usamos Map<String, AccountValue> para flexibilidade na estrutura de resposta.
 */
@Data
@AllArgsConstructor
@Slf4j
public class AccountSummaryDTO {

    // O campo 'BASE' geralmente contém os valores agregados mais importantes
    private Map<String, AccountValue> BASE;

    /**
     * Inner class para representar os valores internos da conta.
     */
    @Data
    public static class AccountValue {
        private BigDecimal buyingPower;
        private BigDecimal netliquidationvalue; // Patrimônio líquido (Liquidez)
        private BigDecimal cashbalance;        // Saldo de caixa
        private BigDecimal totalcashbalance;   // Poder de Compra (Buying Power - Campo pode variar)
        private String currency;               // Moeda (Ex: "USD", "BASE")

        // 🚨 CAMPOS CRÍTICOS DE MARGEM ADICIONADOS
        private BigDecimal excessLiquidity;      // Excesso de Liquidez (Mais importante que o BP)
        private BigDecimal initMarginReq;        // Margem Inicial Requerida
        private BigDecimal maintMarginReq;       // Margem de Manutenção Requerida
    }

    /**
     * ✅ Lógica Robusta de Buying Power: Implementa a hierarquia de fallback para garantir
     * que o valor mais seguro e não-zero seja retornado. Prioridade: EL > Cash > Total Cash > BP.
     * @return O valor de liquidez mais confiável encontrado, ou zero.
     */
    public BigDecimal getBuyingPower() {
        // 1. Condição de verificação de que o bloco 'BASE' existe
        if (BASE != null && BASE.get("BASE") != null) {
            AccountValue baseAccount = BASE.get("BASE");

            // 2. VALIDAÇÃO MULTIPLA DE CAMPOS (Priorizando Excess Liquidity)
            if (baseAccount.getExcessLiquidity() != null &&
                    baseAccount.getExcessLiquidity().compareTo(BigDecimal.ZERO) > 0) {
                log.debug("💰 [DTO] Usando ExcessLiquidity (R$ {}) como BuyingPower.", baseAccount.getExcessLiquidity().toPlainString());
                return baseAccount.getExcessLiquidity();
            } else if (baseAccount.getCashbalance() != null &&
                    baseAccount.getCashbalance().compareTo(BigDecimal.ZERO) > 0) {
                log.debug("💰 [DTO] Usando Cashbalance (R$ {}) como BuyingPower.", baseAccount.getCashbalance().toPlainString());
                return baseAccount.getCashbalance();
            } else if (baseAccount.getTotalcashbalance() != null &&
                    baseAccount.getTotalcashbalance().compareTo(BigDecimal.ZERO) > 0) {
                log.warn("⚠️ [DTO] Usando totalcashbalance (R$ {}) como fallback para BuyingPower.", baseAccount.getTotalcashbalance().toPlainString());
                return baseAccount.getTotalcashbalance();
            } else if (baseAccount.getBuyingPower() != null &&
                    baseAccount.getBuyingPower().compareTo(BigDecimal.ZERO) > 0) {
                log.warn("⚠️ [DTO] Usando campo buyingPower explícito (R$ {}) como fallback.", baseAccount.getBuyingPower().toPlainString());
                return baseAccount.getBuyingPower();
            }
        }

        log.error("❌ [DTO] Nenhum campo válido encontrado para BuyingPower em BASE. Retornando ZERO.");
        return BigDecimal.ZERO;
    }

    /**
     * Adiciona um método de diagnóstico para facilitar a depuração e verificação dos campos brutos da API.
     */
    public void debugBaseFields() {
        if (BASE != null && BASE.get("BASE") != null) {
            AccountValue base = BASE.get("BASE");
            log.info("🔍 [DEBUG DTO] BASE Fields - EL: R$ {}, Cash: R$ {}, TotalCash: R$ {}, BP: R$ {}, NLV: R$ {}, InitMargin: R$ {}, MaintMargin: R$ {}",
                    base.getExcessLiquidity(),
                    base.getCashbalance(),
                    base.getTotalcashbalance(),
                    base.getBuyingPower(),
                    base.getNetliquidationvalue(),
                    base.getInitMarginReq(),
                    base.getMaintMarginReq());
        } else {
            log.warn("🔍 [DEBUG DTO] BASE ou BASE.BASE é null. Não foi possível inspecionar os campos.");
        }
    }
}
