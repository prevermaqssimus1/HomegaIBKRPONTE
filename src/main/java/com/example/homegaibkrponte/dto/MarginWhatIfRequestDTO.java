package com.example.homegaibkrponte.dto;

import com.example.homegaibkrponte.domain.OrderSide;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/**
 * DTO para receber o request de simulação de margem 'What-If' do Principal.
 * Adapta a estrutura de dados para o processamento interno da Ponte.
 */
@Getter
@Setter
public class MarginWhatIfRequestDTO {
    private String accountId;
    private String symbol;
    private OrderSide side; // Enum que deve existir na Ponte (ou ser mapeado)
    private BigDecimal quantity;
    private BigDecimal lastPrice;
    private String venue;
}