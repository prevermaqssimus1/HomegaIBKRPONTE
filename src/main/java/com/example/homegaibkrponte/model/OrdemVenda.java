package com.example.homegaibkrponte.model;

import java.math.BigDecimal;
import java.util.UUID;

public record OrdemVenda(
        String ativo,
        BigDecimal quantidadeVenda,
        String motivo, // ex: "LIQUIDEZ_GDL_PROFIT"
        UUID transactionId // Para idempotência e rastreamento na Ponte
) {}