package com.example.homegaibkrponte.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

// Enum já definido anteriormente
public enum LiquidityMode {
    HIGH_LIQUIDITY, // Modo normal, utiliza regras completas de divisão
    SAFE_MODE,      // Modo proteção, ordens menores, ativado por BP/Custo baixo
    EMERGENCY,      // Novo modo: Margem de Reserva ou Cash críticos, GDL obrigatória
    RECOVERY        // Modo recuperação, reage a queda repentina de BP/Cash

}