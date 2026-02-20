package com.example.homegaibkrponte.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * DTO de Tick de Mercado na PONTE.
 * Transporta o preço real-time da TWS para o sistema Principal.
 */
public record MarketTickDTO(
        @JsonProperty("symbol") String symbol,
        @JsonProperty("price") BigDecimal price,
        @JsonProperty("currency") String currency
) {}