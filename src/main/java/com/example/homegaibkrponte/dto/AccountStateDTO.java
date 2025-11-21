package com.example.homegaibkrponte.dto;

// Pacote com.example.homegaibkrponte.rest.dto
// (Ou o pacote DTO da Ponte, assumindo que a Ponte usa um modelo similar ao AccountState)

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import java.math.BigDecimal;
import java.time.Instant;

@Builder
public record AccountStateDTO(
        @JsonProperty("netLiquidation") BigDecimal netLiquidation,
        @JsonProperty("cashBalance") BigDecimal cashBalance,
        @JsonProperty("buyingPower") BigDecimal buyingPower,
        @JsonProperty("excessLiquidity") BigDecimal excessLiquidity,
        @JsonProperty("initMarginReq") BigDecimal initMarginReq,
        @JsonProperty("maintainMarginReq") BigDecimal maintainMarginReq,
        @JsonProperty("availableFunds") BigDecimal availableFunds,
        @JsonProperty("currency") String currency,
        @JsonProperty("timestamp") Instant timestamp
) {}