package com.example.homegaibkrponte.service;

import com.example.homegaibkrponte.model.PosicaoAvaliada;
import java.math.BigDecimal;
import java.util.List;

public interface AccountStateProvider {
    /** Retorna o Cash Balance atual. */
    BigDecimal getCurrentCashBalance();

    /** Retorna o Portfólio Avaliado mais fresco (Posição + PnL). */
    List<PosicaoAvaliada> getCurrentEvaluatedPortfolio();
}