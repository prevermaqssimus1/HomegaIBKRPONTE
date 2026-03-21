package com.example.homegaibkrponte.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public record MarketTickDTO(
        @JsonProperty("symbol") String symbol,
        @JsonProperty("price") BigDecimal price, // Last Price
        @JsonProperty("bid") BigDecimal bid,     // OBRIGATÓRIO para Delta
        @JsonProperty("ask") BigDecimal ask,     // OBRIGATÓRIO para Delta
        @JsonProperty("size") Long size,         // Volume do Tick
        @JsonProperty("t") Long t                // Timestamp da TWS
) {}