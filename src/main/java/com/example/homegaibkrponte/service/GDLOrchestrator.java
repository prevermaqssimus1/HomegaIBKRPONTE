package com.example.homegaibkrponte.service;

// Pacote: com.example.homegaibkrponte.service

import com.example.homegaibkrponte.model.OrdemVenda;
import com.example.homegaibkrponte.model.PosicaoAvaliada;
import com.example.homegaibkrponte.model.SinalCompra;
import com.example.homegaibkrponte.model.SignalState;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class GDLOrchestrator {

    private final LiquidityGeneratorService generator;
    // Simulação do serviço que envia para a Ponte IBKR
    // private final IBKRExecutionService ibkrExecutionService;

    public GDLOrchestrator(LiquidityGeneratorService generator) {
        this.generator = generator;
    }

    /**
     * Tenta gerar e despachar uma Ordem de Venda.
     * @return SinalCompra no estado ESPERA_BP se a venda for enviada.
     */
    public Optional<SinalCompra> acionarGDL(SinalCompra sinal, BigDecimal bpNecessario, List<PosicaoAvaliada> portfolio) {

        // 1. Gera a venda (colheita de lucro)
        Optional<OrdemVenda> vendaOpt = generator.gerarVenda(bpNecessario, portfolio);

        if (vendaOpt.isPresent()) {
            OrdemVenda venda = vendaOpt.get();

            // 2. Envia para a Ponte IBKR para execução (simulado)
            // ibkrExecutionService.enviarVenda(venda);

            // LOG ESTRUTURADO DE COMPROVAÇÃO - Passo 4: PAUSA/ESPERA
            // log.warn("[signalId={}] [action=PONTE_VENDA_ENVIADA] status=ESPERA_BP, msg='Aguardando confirmação assíncrona da Ponte.'", sinal.signalId());
            System.out.printf("GDLOrchestrator: Sinal [%s] em estado ESPERA_BP. Enviada venda de %s.%n",
                    sinal.signalId(), venda.ativo());

            // 3. Muda o estado para ESPERA_BP, pausando o fluxo principal (Crucial para a sinergia)
            return Optional.of(sinal.comNovoEstado(SignalState.ESPERA_BP));
        }

        // Se não foi possível gerar liquidez, o sinal pode ser pausado (manter estado PENDING_BUY para re-retry)
        return Optional.empty();
    }

    /**
     * Método que seria chamado por um callback da Ponte IBKR após a atualização de BP.
     * Comprovação de Callback - Passo 5
     */
    public SinalCompra processarCallbackBP(UUID signalId, BigDecimal novoBp) {
        // Aqui a lógica real recuperaria o sinal da fila/estado
        // log.info("[signalId={}] [action=CONFIRMACAO_BP] bpAntes={}, bpDepois={}, fonte=IBKR_UPDATE", signalId, bpAnterior, novoBp);
        System.out.printf("GDLOrchestrator: Recebido callback de BP para Sinal [%s]. Novo BP: %s%n", signalId, novoBp);

        // Retorna o Sinal em estado PENDING_BUY para reprocessamento
        // Na prática, a lógica do processador principal faria o reprocessamento.
        return new SinalCompra(signalId, "REPROCESSAR", BigDecimal.ZERO, SignalState.PENDING_BUY);
    }
}