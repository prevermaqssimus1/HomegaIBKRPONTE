package com.example.homegaibkrponte.service;

// Pacote: com.example.homegaibkrponte.service

import com.example.homegaibkrponte.model.OrdemVenda;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.homegaibkrponte.model.PosicaoAvaliada;
import org.springframework.stereotype.Service;

@Service
public class LiquidityGeneratorService {

    private static final BigDecimal FRACAO_MAX_VENDA = new BigDecimal("0.10"); // Vende no máximo 10% da posição

    /**
     * Tenta gerar uma OrdemVenda para cobrir o BP necessário, priorizando PnL não realizado.
     */
    public Optional<OrdemVenda> gerarVenda(BigDecimal bpNecessario, List<PosicaoAvaliada> portfolio) {
        List<PosicaoAvaliada> lucrativas = portfolio.stream()
                // 1. Filtrar posições lucrativas e com quantidade > 0
                .filter(p -> p.lucroNaoRealizado().compareTo(BigDecimal.ZERO) > 0)
                .filter(p -> p.quantidade().compareTo(BigDecimal.ZERO) > 0)
                // 2. Priorizar pelo maior PnL não realizado
                .sorted(Comparator.comparing(PosicaoAvaliada::lucroNaoRealizado).reversed())
                .toList();

        if (lucrativas.isEmpty()) {
            System.out.println("GDL: nenhuma posição lucrativa disponível para colheita.");
            return Optional.empty();
        }

        PosicaoAvaliada alvo = lucrativas.get(0);
        BigDecimal maxVendaPermitida = alvo.quantidade().multiply(FRACAO_MAX_VENDA);

        // Simplesmente para o MVP: a quantidade a vender é o mínimo entre o que é permitido
        // vender e o BP necessário (assumindo que o PnL é igual à quantidade * preço, o que não é 100% real, mas é um bom proxy para o MVP)
        BigDecimal quantidadeVenda = maxVendaPermitida.min(bpNecessario);

        if (quantidadeVenda.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }

        OrdemVenda venda = new OrdemVenda(
                alvo.ativo(),
                quantidadeVenda,
                "LIQUIDEZ_GDL_PROFIT",
                UUID.randomUUID()
        );

        System.out.printf("GDL: gerada OrdemVenda [%s] de %s para cobrir %s de BP%n",
                venda.transactionId(), venda.ativo(), bpNecessario);

        // LOG ESTRUTURADO DE COMPROVAÇÃO - Passo 3: VENDA GERADA
        // log.info("[signalId={}] [action=VENDA_GERADA] ativo={}, qtdVenda={}, motivo={}, transactionId={}",
        //          signalId, venda.ativo(), venda.quantidadeVenda(), venda.motivo(), venda.transactionId());

        return Optional.of(venda);
    }
}