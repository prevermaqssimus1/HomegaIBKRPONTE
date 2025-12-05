package com.example.homegaibkrponte.service;

import com.example.homegaibkrponte.config.OrdensConfig;
import com.example.homegaibkrponte.model.Position;
import com.example.homegaibkrponte.model.PosicaoAvaliada; // NOVO DTO
import com.example.homegaibkrponte.model.SinalVenda;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class GDLService {

    private final OrdensConfig config;

    public GDLService(OrdensConfig config) {
        this.config = config;
    }

    /**
     * Tenta gerar um SinalVenda seletivo para liberar liquidez, usando PosicaoAvaliada (Principal).
     * @param bpNecessario A quantidade monetária de BP que precisamos gerar.
     * @param portfolioAvaliado O portfólio avaliado (inclui PnL).
     * @return Um SinalVenda opcional.
     */
    public Optional<SinalVenda> tentarGerarVenda(BigDecimal bpNecessario, List<PosicaoAvaliada> portfolioAvaliado) {

        // 1. Filtrar e priorizar posições lucrativas (PnL > 0)
        List<PosicaoAvaliada> lucrativas = portfolioAvaliado.stream()
                .filter(p -> p.lucroNaoRealizado().compareTo(BigDecimal.ZERO) > 0)
                .filter(p -> p.posicaoBase().getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparing(PosicaoAvaliada::lucroNaoRealizado).reversed())
                .toList();

        if (lucrativas.isEmpty()) {
            System.out.println("🚨 [GDL - Principal] Nenhuma posição lucrativa para colheita.");
            return Optional.empty();
        }

        PosicaoAvaliada alvoAvaliado = lucrativas.getFirst();
        Position alvo = alvoAvaliado.posicaoBase(); // Posição original da Ponte

        // 2. Cálculo de Quantidade Baseado em Porcentagem (Regra do Principal)
        BigDecimal fracaoMaxima = config.getFracaoMaxVendaGDL();
        BigDecimal maxVendaPermitida = alvo.getQuantity().multiply(fracaoMaxima); // Usa getQuantity() da Ponte

        BigDecimal quantidadeVenda = maxVendaPermitida.setScale(0, RoundingMode.DOWN);

        if (quantidadeVenda.compareTo(BigDecimal.ONE) < 0) {
            System.out.printf("🚨 [GDL - Principal] Venda muito pequena para %s. Quantidade: %s. Rejeitado.%n",
                    alvo.getSymbol(), quantidadeVenda);
            return Optional.empty();
        }

        SinalVenda venda = new SinalVenda(alvo.getSymbol(), quantidadeVenda, "LIQUIDEZ_GDL_PROFIT");

        System.out.printf("✅ [GDL - Principal] Gerando Venda de %s (%s) para liberar BP. Max Frac: %.2f%%%n",
                venda.quantidadeVenda().setScale(0, RoundingMode.DOWN),
                alvo.getSymbol(),
                fracaoMaxima.multiply(new BigDecimal("100")));

        return Optional.of(venda);
    }
}