package com.example.homegaibkrponte.divisor;

import com.example.homegaibkrponte.config.OrdensConfig;
import com.example.homegaibkrponte.model.*;
import com.example.homegaibkrponte.service.GDLOrchestrator;
import com.example.homegaibkrponte.service.IBKRConnectorInterface;
import com.example.homegaibkrponte.service.LiquidityManager;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.math.RoundingMode; // Necessário para evitar erro no divide

@Component
public class DivisorDeOrdens {

    private final OrdensConfig config;
    private final LiquidityManager liquidityManager;
    private final GDLOrchestrator gdlOrchestrator;
    private final IBKRConnectorInterface ponteCliente;

    public DivisorDeOrdens(OrdensConfig config,
                           LiquidityManager liquidityManager,
                           GDLOrchestrator gdlOrchestrator,
                           IBKRConnectorInterface ponteCliente) {
        this.config = config;
        this.liquidityManager = liquidityManager;
        this.gdlOrchestrator = gdlOrchestrator;
        this.ponteCliente = ponteCliente;
    }

    /**
     * Processa um Sinal de Compra, coordenando a avaliação de liquidez e o acionamento da GDL.
     * Recebe todos os valores de liquidez em TEMPO REAL (BP, NLV, Margem, Cash) do SSOT da Ponte.
     */
    public List<OrdemCompra> processarSinal(
            SinalCompra sinal,
            BigDecimal bpDisponivelAtual,
            BigDecimal nlv,
            BigDecimal cash,
            BigDecimal reserveMarginFracAtual,
            List<PosicaoAvaliada> portfolioAtualizado) {

        // 1. Avaliação de Liquidez e Modo (Mode) - Uso dos valores EM TEMPO REAL
        LiquidityMode mode = liquidityManager.avaliar(
                bpDisponivelAtual, nlv, sinal.custoTotal(), cash, reserveMarginFracAtual
        );

        // Logs de Comprovação - Passo 1: AVALIACAO_INICIAL (Audit Trail)
        System.out.printf("Divisor: Sinal [%s] avaliado em modo %s. BP: %s, Custo: %s, Margem: %.2f%%%n",
                sinal.signalId(), mode, bpDisponivelAtual, sinal.custoTotal(),
                reserveMarginFracAtual.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP));

        // 2. Ajuste de BP e Verificação
        BigDecimal bpAjustado = liquidityManager.ajustarBPParaOperacao(bpDisponivelAtual, mode);
        boolean bpInsuficiente = sinal.custoTotal().compareTo(bpAjustado) > 0;

        // --- GATE GDL/EMERGENCY ---
        if (bpInsuficiente && mode != LiquidityMode.HIGH_LIQUIDITY) {

            BigDecimal bpNecessario = sinal.custoTotal().subtract(bpAjustado);

            // 3. Acionamento do GDLOrchestrator
            // O Orchestrator lida com a geração da venda, o envio via ponte, e a transição para ESPERA_BP.
            Optional<SinalCompra> novoSinalOpt = gdlOrchestrator.acionarGDL(
                    sinal.comNovoEstado(mode == LiquidityMode.EMERGENCY ? SignalState.GDL_EM_EXECUCAO : SignalState.PENDING_BUY),
                    bpNecessario,
                    portfolioAtualizado
            );

            if (novoSinalOpt.isPresent() && novoSinalOpt.get().estado() == SignalState.ESPERA_BP) {
                // Sinergia Perfeita: O GDLOrchestrator enviou a venda e PAUSOU o sinal.
                // O Divisor deve parar e aguardar o callback assíncrono do novo BP.
                System.out.println("⚠️ [ORQUESTRADOR GDL] GDL acionada. Pausando divisão. Sinal em ESPERA_BP.");
                return List.of();
            }

            // Se a GDL não pôde ser acionada (ex: sem posições lucrativas) e o BP é insuficiente, pausamos.
            if (!novoSinalOpt.isPresent()) {
                System.out.printf("❌ [LIQUIDEZ CRÍTICA] Sinal [%s] pausado. GDL não gerou liquidez (sem lucro ou EMERGENCY).%n", sinal.signalId());
                return List.of();
            }
        }
        // --- FIM GATE GDL/EMERGENCY ---


        // 4. Divisão Tradicional (Executa se o BP for adequado ou se for HIGH_LIQUIDITY)
        if (!bpInsuficiente || mode == LiquidityMode.HIGH_LIQUIDITY) {
            System.out.printf("Divisor: Sinal [%s] executando divisão. BP ajustado para uso: %s%n", sinal.signalId(), bpAjustado);
            return dividir(sinal, bpAjustado);
        }

        // Caso o sinal tenha chegado ao fim do fluxo de verificação sem acionar GDL e sem BP suficiente,
        // ele é pausado para evitar o erro 201 do Broker.
        System.out.printf("❌ [LIQUIDEZ CRÍTICA] Sinal [%s] pausado por BP insuficiente (Custos de %s > %s disponível).%n",
                sinal.signalId(), sinal.custoTotal(), bpDisponivelAtual);
        return List.of();
    }

    /**
     * Contém a lógica de divisão de ordens grandes em menores (originalmente existente).
     * @param bpDisponivelParaUso É o BP ajustado pelo LiquidityManager.
     */
    private List<OrdemCompra> dividir(SinalCompra sinal, BigDecimal bpDisponivelParaUso) {
        // Exemplo da sua lógica de divisão
        BigDecimal custoPorOrdem = sinal.custoTotal().divide(new BigDecimal("3"), 2, RoundingMode.HALF_UP);

        System.out.printf("Divisor: Criando 3 sub-ordens de compra para %s (BP máximo: %s).%n",
                sinal.ativo(), bpDisponivelParaUso);

        // ✅ CORREÇÃO: Adicionada a terceira propriedade BigDecimal (BigDecimal.ZERO)
        // para satisfazer o construtor de 5 argumentos do record OrdemCompra.
        return List.of(
                new OrdemCompra(sinal.ativo(), custoPorOrdem, BigDecimal.ZERO, BigDecimal.ZERO, UUID.randomUUID()),
                new OrdemCompra(sinal.ativo(), custoPorOrdem, BigDecimal.ZERO, BigDecimal.ZERO, UUID.randomUUID()),
                new OrdemCompra(sinal.ativo(), custoPorOrdem, BigDecimal.ZERO, BigDecimal.ZERO, UUID.randomUUID())
        );
        // O resultado dessa lista seria enviado para a PonteCliente para execução.
    }
}