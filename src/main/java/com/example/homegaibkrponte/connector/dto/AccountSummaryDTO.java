package com.example.homegaibkrponte.connector.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Representa os dados resumidos da conta obtidos via GET /portfolio/{accountId}/summary.
 * Usamos Map<String, AccountValue> para flexibilidade, pois a API retorna muitos campos aninhados.
 */
@Data
@AllArgsConstructor // <-- ADICIONE ISTO
@Slf4j
public class AccountSummaryDTO {

    // O campo 'BASE' geralmente contém os valores agregados mais importantes
    private Map<String, AccountValue> BASE;

    // Inner class para representar os valores internos
    @Data
    public static class AccountValue {
        private BigDecimal buyingPower;
        private BigDecimal netliquidationvalue; // Patrimônio líquido (Liquidez)
        private BigDecimal cashbalance;        // Saldo de caixa
        private BigDecimal totalcashbalance;   // Poder de Compra (Buying Power - Campo pode variar)
        private String currency;               // Moeda (Ex: "USD", "BASE")
        // ... Outros campos relevantes (margem, etc.)
    }

    /**
     * Método auxiliar para fácil acesso ao Buying Power.
     * Assume que os dados importantes estão sob a chave "BASE" e usa 'cashbalance' como proxy.
     */
    public BigDecimal getBuyingPower() {
        if (BASE != null && BASE.get("BASE") != null) {
            // Chamando o getter gerado pelo @Data na AccountValue.
            // Isso resolve o erro de 'cannot find symbol' se o Lombok estiver rodando.
            return BASE.get("BASE").getCashbalance();
        }
        return BigDecimal.ZERO;
    }


}