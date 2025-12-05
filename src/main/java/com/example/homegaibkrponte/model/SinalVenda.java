package com.example.homegaibkrponte.model;

import java.math.BigDecimal;

/**
 * Representa um sinal de VENDA gerado internamente pela GDL
 * para recompor a liquidez. Deve ser enviado à Ponte (IBKR Connector).
 */
public record SinalVenda(
        String ativo,
        BigDecimal quantidadeVenda,
        String motivo // ex: "LIQUIDEZ_GDL_PROFIT"
) {}