package com.example.homegaibkrponte.model;

import java.time.LocalDateTime;
/**
 * Modelo canônico e imutável para um candle de preço.
 */
public record Candle(String symbol, LocalDateTime timestamp, double open, double high, double low, double close, long volume) {}
