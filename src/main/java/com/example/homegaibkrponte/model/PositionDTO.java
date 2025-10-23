package com.example.homegaibkrponte.model;

import lombok.Data;
import java.math.BigDecimal;

/**
 * Representa uma única posição no portfólio obtida via GET /portfolio/{accountId}/positions.
 */
@Data
public class PositionDTO {

    private int conid;             // ID do Contrato
    private String ticker;         // Ticker do Ativo (Ex: "AAPL")
    private BigDecimal position;   // Tamanho da posição (Ex: 100.0)
    private BigDecimal mktPrice;   // Preço de mercado
    private BigDecimal unrealizedPNL; // PNL não realizado
    private String account;        // ID da conta
    private String currency;       // Moeda da posição

    // ... Outros campos relevantes (custo médio, etc.)
}