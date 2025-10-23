package com.example.homegaibkrponte.model;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Representa um snapshot imutável do estado do portfólio.
 * Utiliza o 'record' do Java para garantir imutabilidade e clareza.
 * AJUSTE: Não armazena mais 'totalCapital' para garantir consistência dos dados.
 */
@Builder(toBuilder = true)
public record Portfolio(
        String symbolForBacktest,
        BigDecimal cashBalance,
        Map<String, Position> openPositions,
        List<Trade> tradeHistory
) {
    /**
     * Construtor de conveniência para iniciar um backtest para um símbolo específico.
     * Esta é a forma limpa e padrão para criar um novo portfólio de simulação.
     *
     * @param symbol O símbolo do ativo ("carro") para esta simulação.
     * @param initialCapital O capital inicial para a simulação.
     */
    public Portfolio(String symbol, BigDecimal initialCapital) {
        this(symbol, initialCapital, Collections.emptyMap(), Collections.emptyList());
    }

    /**
     * Calcula o valor total atual do portfólio (equity) sob demanda.
     *
     * @param lastPrice O preço de mercado mais recente para o ativo das posições.
     * @return O valor total do portfólio.
     */
    public BigDecimal calculateCurrentValue(BigDecimal lastPrice) { // AJUSTE: Recebe BigDecimal para consistência
        if (openPositions == null || openPositions.isEmpty()) {
            return this.cashBalance;
        }

        BigDecimal positionsValue = BigDecimal.ZERO;
        for (Position position : openPositions.values()) {
            // Este cálculo assume um portfólio de um único ativo, que é o contexto do backtest.
            positionsValue = positionsValue.add(position.getQuantity().multiply(lastPrice));
        }
        return this.cashBalance.add(positionsValue);
    }

    /**
     * Verifica se existe uma posição aberta para um determinado símbolo.
     *
     * @param symbol O ticker do ativo a ser verificado (ex: "PETR4").
     * @return 'true' se uma posição para o símbolo existir, 'false' caso contrário.
     */
    public boolean hasOpenPosition(String symbol) {
        return openPositions != null && openPositions.containsKey(symbol);
    }
}