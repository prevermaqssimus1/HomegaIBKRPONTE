package com.example.homegaibkrponte.divisor;

import com.example.homegaibkrponte.model.SinalCompra;
import com.example.homegaibkrponte.model.OrdemCompra;
import com.example.homegaibkrponte.model.PosicaoAvaliada;
import com.example.homegaibkrponte.divisor.DivisorDeOrdens; // Deve estar disponível
import com.example.homegaibkrponte.service.AccountStateProvider;
import com.example.homegaibkrponte.service.BPSyncedListener;
import com.example.homegaibkrponte.service.IBKRConnectorInterface;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Gerencia o ciclo de vida das ordens e atua como Listener de sincronia de BP.
 * É o ponto de controle que orquestra a GDL e retoma o processamento de forma assíncrona.
 * Implementa sinergia de estado usando AccountStateProvider para dados frescos.
 */
public class OrderFlowManager implements BPSyncedListener {

    private final DivisorDeOrdens divisor;
    private final IBKRConnectorInterface ponteCliente;
    private final AccountStateProvider stateProvider; // <== NOVO CAMPO: Provedor de Estado Fresco

    private Optional<SinalCompra> sinalEmEspera = Optional.empty();

    // REMOVIDO: Armazenamento de estado obsoleto (cashAntesDaGDL, portfolioAntesDaGDL)


    public OrderFlowManager(DivisorDeOrdens divisor,
                            IBKRConnectorInterface ponteCliente,
                            AccountStateProvider stateProvider) { // <== Novo parâmetro para sinergia
        this.divisor = divisor;
        this.ponteCliente = ponteCliente;
        this.stateProvider = stateProvider; // <== Armazena o provedor

        // Sinergia: O Principal se registra na Ponte
        this.ponteCliente.setBPSyncedListener(this);
        System.out.println("⚙️ [FLOW_MANAGER] Registrado como Listener na Ponte IBKR.");
    }

    /**
     * Ponto de entrada do processamento de um novo sinal.
     */
    public List<OrdemCompra> processarNovoSinal(
            SinalCompra sinal,
            BigDecimal bpDisponivelAtual,
            BigDecimal nlv,
            BigDecimal cash,
            BigDecimal reserveMarginFracAtual,
            List<PosicaoAvaliada> portfolioAvaliado) {

        // Não armazenamos mais os dados obsoletos, mas usamos eles na primeira chamada ao Divisor.

        List<OrdemCompra> ordens = divisor.processarSinal(
                sinal, bpDisponivelAtual, nlv, cash, reserveMarginFracAtual, portfolioAvaliado
        );

        // Se o Divisor retornar vazio (GDL acionada), armazenamos o sinal para a retomada assíncrona.
        if (ordens.isEmpty() && sinalEmEspera.isEmpty()) {
            System.out.println("⚠️ [FLOW_MANAGER] Sinal pausado. Aguardando callback da GDL.");
            this.sinalEmEspera = Optional.of(sinal);
        }

        return ordens; // Retorna vazio se a GDL foi acionada.
    }


    /**
     * ✅ CALLBACK DE SINCRONIA: Chamado assincronamente pela Ponte.
     */
    @Override
    public void onBPSynced(BigDecimal novoBp, BigDecimal novoNlv, BigDecimal novaReserveMarginFrac) {
        System.out.println("✅ [ORDER_FLOW_MANAGER] Callback recebido! BP e NLV atualizados.");

        if (sinalEmEspera.isPresent()) {
            SinalCompra sinal = sinalEmEspera.get();
            System.out.printf("🔄 [ORDER_FLOW_MANAGER] Retomando processamento para sinal %s...%n", sinal.ativo());

            // 🚨 AJUSTE CRÍTICO DE SINERGIA: Busca os dados de estado mais frescos (Cash e Portfólio)
            BigDecimal cashAtualizado = stateProvider.getCurrentCashBalance();
            List<PosicaoAvaliada> portfolioAtualizado = stateProvider.getCurrentEvaluatedPortfolio();

            // Retoma o processamento com os NOVOS dados de liquidez da Ponte
            List<OrdemCompra> ordensRetomadas = divisor.processarSinal(
                    sinal,
                    novoBp,                     // NOVO BP da Ponte
                    novoNlv,                    // NOVO NLV da Ponte
                    cashAtualizado,             // CASH ATUALIZADO do Provedor
                    novaReserveMarginFrac,      // NOVA MARGEM da Ponte
                    portfolioAtualizado         // PORTFÓLIO ATUALIZADO do Provedor
            );

            System.out.printf("✅ [ORDER_FLOW_MANAGER] Retomada gerou %d ordens.%n", ordensRetomadas.size());

            // Enviar as ordens de compra geradas para a Ponte
            for (OrdemCompra ordem : ordensRetomadas) {
                this.ponteCliente.enviarOrdemDeCompra(ordem);
            }

            this.sinalEmEspera = Optional.empty(); // Limpa o estado
        } else {
            System.out.println("⚠️ [ORDER_FLOW_MANAGER] BP sincronizado, mas nenhum sinal em espera. Ignorando evento.");
        }
    }
}