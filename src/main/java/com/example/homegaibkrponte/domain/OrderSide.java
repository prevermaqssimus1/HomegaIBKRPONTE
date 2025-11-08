package com.example.homegaibkrponte.domain;

// Define os lados da ordem
public enum OrderSide {
    BUY,
    SELL,
    BUY_TO_COVER, // Usado para fechar short
    SELL_SHORT    // Usado para abrir short
}