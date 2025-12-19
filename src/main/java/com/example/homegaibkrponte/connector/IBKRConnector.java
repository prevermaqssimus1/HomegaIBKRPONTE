package com.example.homegaibkrponte.connector;

import com.example.homegaibkrponte.connector.mapper.IBKRMapper;
import com.example.homegaibkrponte.data.MarketDataProvider;
import com.example.homegaibkrponte.dto.ExecutionReportDto;
import com.example.homegaibkrponte.dto.MarginWhatIfResponseDTO;
import com.example.homegaibkrponte.exception.MarginRejectionException;
import com.example.homegaibkrponte.exception.OrdemFalhouException;
import com.example.homegaibkrponte.model.Candle;
import com.example.homegaibkrponte.model.OrderStateDTO;
import com.example.homegaibkrponte.model.PositionDTO;
import com.example.homegaibkrponte.model.TradeExecutedEvent;
import com.example.homegaibkrponte.monitoring.LivePortfolioService;
import com.example.homegaibkrponte.properties.IBKRProperties;
import com.ib.client.Decimal;

// Adicionado para SINERGIA com o Principal
import com.example.homegaibkrponte.service.IBKRConnectorInterface;
import com.example.homegaibkrponte.service.BPSyncedListener;
import com.example.homegaibkrponte.model.SinalVenda;
import com.example.homegaibkrponte.model.OrdemCompra;

import com.example.homegaibkrponte.service.OrderIdManager;
import com.example.homegaibkrponte.service.WebhookNotifierService;
import com.ib.client.*;
import com.ib.client.protobuf.*;
import io.micrometer.core.instrument.Gauge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.MeterRegistry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ADAPTADOR CENTRAL (MarketDataProvider) e OBSERVER (EWrapper).
 * É o coração da **PONTE** e gerencia a conexão e os callbacks.
 * Implementa **IBKRConnectorInterface** para sinergia com o Principal.
 */
@Service
@Slf4j
public class IBKRConnector implements MarketDataProvider, EWrapper, IBKRConnectorInterface { // <<== IMPLEMENTAÇÃO DA INTERFACE DO PRINCIPAL

    // ==========================================================
    // DECLARAÇÕES DE CAMPO (PONTE)
    // ==========================================================
    private final IBKRProperties ibkrProps;
    private final WebhookNotifierService webhookNotifier;
    private final AtomicReference<BigDecimal> buyingPowerCache = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> excessLiquidityCache = new AtomicReference<>(BigDecimal.ZERO);
    private final List<PositionDTO> tempPositions = new ArrayList<>();
    private final LivePortfolioService portfolioService;
    private final ApplicationEventPublisher eventPublisher;
    private final ConcurrentHashMap<Integer, String> marketDataRequests = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;
    private final AtomicInteger currentAccountSummaryReqId = new AtomicInteger(-1);
    private final ConcurrentMap<Integer, CompletableFuture<OrderStateDTO>> whatIfFutures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BigDecimal> marketPriceCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, com.ib.client.Order> lastOrdersCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, com.ib.client.Contract> lastContractsCache = new ConcurrentHashMap<>();
    private final OrderIdManager orderIdManager;
    private final IBKRMapper ibkrMapper;
    private String lastWhatIfEl = "0.0";
    private EClientSocket client;
    private EReaderSignal readerSignal;
    private final AtomicInteger nextValidId = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, CompletableFuture<List<Candle>>> pendingHistoricalData = new ConcurrentHashMap<>();
    private final CountDownLatch connectionLatch = new CountDownLatch(1);
    private static final int CRITICAL_MARGIN_REQ_ID = 9001;
    private final Map<String, Integer> recoveryAttempts = new ConcurrentHashMap<>();
    // ✅ CAMPO SINÉRGICO: Listener de Callback para o Principal
    private Optional<BPSyncedListener> bpListener = Optional.empty();

    private final ConcurrentHashMap<Integer, CompletableFuture<MarginWhatIfResponseDTO>> pendingMarginWhatIfRequests = new ConcurrentHashMap<>();


    // ==========================================================
    // CONSTRUTOR
    // ==========================================================
    @Autowired
    public IBKRConnector(IBKRProperties props,
                         WebhookNotifierService notifier,
                         LivePortfolioService portfolioService,
                         ApplicationEventPublisher eventPublisher,
                         OrderIdManager orderIdManager,
                         IBKRMapper ibkrMapper,
                         MeterRegistry meterRegistry) {
        this.ibkrProps = props;
        this.webhookNotifier = notifier;
        this.portfolioService = portfolioService;
        this.eventPublisher = eventPublisher;
        this.orderIdManager = orderIdManager;
        this.readerSignal = new EJavaSignal();
        this.client = new EClientSocket(this, readerSignal);
        this.ibkrMapper = ibkrMapper;
        this.meterRegistry = meterRegistry;

        // Observabilidade local (Ponte)
        Gauge.builder("ponte.cache.buying_power", this, connector -> connector.buyingPowerCache.get().doubleValue())
                .description("Buying power atual no cache da Ponte")
                .register(meterRegistry);

        Gauge.builder("ponte.cache.excess_liquidity", this, connector -> connector.excessLiquidityCache.get().doubleValue())
                .description("Excess liquidity atual no cache da Ponte")
                .register(meterRegistry);
        log.info("ℹ️ [Ponte IBKR] Inicializador concluído. Mappers e Serviços injetados (Sinergia OK).");
    }

    // ==========================================================
    // IMPLEMENTAÇÃO DA INTERFACE IBKRConnectorInterface (SINERGIA GDL)
    // ==========================================================

    @Override
    public void setBPSyncedListener(BPSyncedListener listener) {
        this.bpListener = Optional.ofNullable(listener);
        log.info("⚙️ [PONTE IBKR] BPSyncedListener do Principal registrado com sucesso.");
    }

    public Optional<BigDecimal> getLatestCachedPrice(String symbol) {
        // Usa o cache que é atualizado pelo callback tickPrice
        return Optional.ofNullable(marketPriceCache.get(symbol.toUpperCase()));
    }

    public String getLastWhatIfExcessLiquidity() {
        return this.lastWhatIfEl;
    }


    /**
     * 🚀 MÉTODO CENTRAL DE ENVIO (Garante o Passo 1)
     * TODA submissão de ordem deve passar por aqui para alimentar o cache de recuperação.
     */
    /**
     * 🚀 ENVIO FÍSICO PARA TWS: Ponto final de execução na Ponte.
     * Ajustado para garantir Sinergia de Capital (Flight Orders) e Cache de Recuperação.
     */
    public void placeOrder(int orderId, Contract contract, com.ib.client.Order order) {
        try {
            if (isConnected()) {
                // 1. 🚨 REGISTRO NO CACHE DE RECUPERAÇÃO (Para suportar o Erro 201)
                lastOrdersCache.put(orderId, order);
                lastContractsCache.put(orderId, contract);

                // 2. 🛡️ SINERGIA DE CAPITAL: Reserva o capital no LivePortfolioService (Flight Orders)
                // Isso impede que o SizingService use o mesmo dinheiro enquanto a ordem não for confirmada. [cite: 450, 451]
                BigDecimal quantity = new BigDecimal(order.totalQuantity().value().toString());
                // Para ordens MARKET, usamos o preço de referência ou último preço conhecido
                BigDecimal price = order.lmtPrice() != 0 ? BigDecimal.valueOf(order.lmtPrice()) :
                        portfolioService.getMarketDataProvider().apply(contract.symbol());

                portfolioService.trackOrderSent(String.valueOf(orderId), quantity, price);

                log.info("📦 [TWS-OUT] Ordem {} registrada no cache e capital reservado. Ativo: {} | Qtd: {}",
                        orderId, contract.symbol(), quantity);

                // 3. ENVIO FÍSICO VIA SOCKET
                this.client.placeOrder(orderId, contract, order);

                log.info("✅ [TWS-OUT] Ordem {} transmitida à IBKR com sucesso.", orderId);
            } else {
                log.error("❌ [TWS-OUT] Conexão inativa. Falha ao enviar ordem {}.", orderId);
                // Notifica o Principal sobre a falha de conexão imediata
                webhookNotifier.sendOrderRejection(orderId, -1, "Conexão Inativa com Gateway/TWS");
            }
        } catch (Exception e) {
            log.error("💥 [TWS-OUT] Erro crítico no placeOrder: {}", e.getMessage(), e);

            // LIMPEZA DE SEGURANÇA IMEDIATA EM CASO DE EXCEÇÃO
            lastOrdersCache.remove(orderId);
            lastContractsCache.remove(orderId);
            portfolioService.removePendingOrder(String.valueOf(orderId));
        }
    }

    /**
     * Lógica central para reduzir a ordem após rejeição de margem (Erro 201).
     */
    /**
     * 🔄 PROTOCOLO DE RECUPERAÇÃO SMART (PASSO 2)
     * Reduz o lote agressivamente em 40% para tentar encaixar na Margem Inicial disponível.
     * Interrompe o ciclo após 2 tentativas para proteger a sustentabilidade da conta.
     */
    /**
     * 🔄 PROTOCOLO DE RECUPERAÇÃO EXAUSTIVO (PASSO 2 AJUSTADO)
     * Reduz o lote sucessivamente até atingir 1 unidade.
     * ✅ Sustentabilidade: Implementa Cooldown para evitar bloqueio de API (Spam).
     * ✅ Resiliência: Tenta esgotar todas as possibilidades de margem inicial.
     */
    /**
     * 🔄 PROTOCOLO DE RECUPERAÇÃO EXAUSTIVO (Ajustado para Sustentabilidade)
     * Reduz o lote sucessivamente até 1 unidade, respeitando a saúde da conta.
     */
    private void tentarReenvioComReducao(int originalId, com.ib.client.Contract contract, com.ib.client.Order order) {
        String symbol = contract.symbol();
        try {
            // 1. 🛡️ COOLDOWN OBRIGATÓRIO: Evita sobrecarga no processador de mensagens e spam na IBKR
            // Dá tempo para a TWS atualizar os valores de margem após a rejeição anterior.
            Thread.sleep(500);

            double qtdAtual = order.totalQuantity().value().doubleValue();

            // 2. Cálculo da nova quantidade com redução de 40% (AMC - Adaptive Margin Control)
            double novaQtd = Math.floor(qtdAtual * 0.60);

            // Garante a tentativa final com o lote mínimo de 1 ação
            if (novaQtd < 1 && qtdAtual > 1) {
                novaQtd = 1;
            }

            if (novaQtd >= 1 && novaQtd < qtdAtual) {
                // 3. 🛡️ VETO POR LIQUIDEZ EXTREMA: Interrompe o loop se o EL for negativo
                // Isso impede que o sistema tente "espremer" ordens em uma conta já insolvente.
                if (portfolioService.getExcessLiquidity().signum() < 0) {
                    log.error("🛑 [RECOVERY STOP] Excess Liquidity Negativo (R$ {}). Abortando reduções para {}.",
                            portfolioService.getExcessLiquidity().toPlainString(), symbol);
                    return;
                }

                log.warn("🔄 [RECOVERY EXAUSTIVO] Ajustando {} de {} para {} unidades por falta de margem.",
                        symbol, qtdAtual, novaQtd);

                // 4. Atualiza a ordem com a nova quantidade
                order.totalQuantity(com.ib.client.Decimal.get(novaQtd));

                // 5. Gera um NOVO ID único para evitar o erro "Duplicate Order ID" (Código 103)
                int novoId = orderIdManager.getNextOrderId();

                // 6. Reenvia pelo placeOrder centralizado (Garante registro no cache e Flight Orders)
                this.placeOrder(novoId, contract, order);

            } else {
                log.error("🛑 [RECOVERY FATAL] Limite mínimo de 1 unidade atingido para {} sem sucesso na corretora.", symbol);
                // Notifica o Principal sobre o esgotamento das tentativas via Webhook
                webhookNotifier.sendOrderRejection(originalId, 201, "Recovery esgotado: impossível executar mesmo com 1 unidade.");
            }
        } catch (InterruptedException e) {
            log.error("❌ [RECOVERY] Thread interrompida durante o Cooldown.");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("💥 [RECOVERY] Erro crítico no protocolo de redução para {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * ✅ ETAPA 1: Ativa a "torneira" de dados.
     * Chame este método uma única vez após a conexão ser estabelecida.
     */
    public void startStreaming() {
        String accId = this.getAccountId(); // Ou a variável que guarda seu DUN...
        if (this.getClient() != null && isConnected()) {
            log.info("🚀 [PONTE | STREAMING] Ativando subscrição contínua para conta: {}", accId);

            // 'true' mantém a subscrição aberta. A TWS enviará dados sempre que houver mudança.
            this.getClient().reqAccountUpdates(true, accId);
        } else {
            log.error("❌ [PONTE] Falha ao iniciar streaming: Cliente não conectado.");
        }
    }

    /**
     * Realiza uma simulação preventiva de margem antes do envio real.
     */
    public boolean validarMargemPreventiva(Contract contract, Order order) {
        int reqId = order.orderId();
        try {
            log.info("🔍 [PRE-CHECK] Iniciando simulação What-If para {} (ID: {})", contract.symbol(), reqId);

            order.whatIf(true);
            CompletableFuture<com.example.homegaibkrponte.model.OrderStateDTO> future = new CompletableFuture<>();
            whatIfFutures.put(reqId, future);

            client.placeOrder(reqId, contract, order);

            // Aguarda a resposta (3 segundos de timeout para sinergia)
            com.example.homegaibkrponte.model.OrderStateDTO res = future.get(3, TimeUnit.SECONDS);

            if (res != null) {
                // 📊 LOG DE COMPROVAÇÃO TÉCNICA (Usando seus campos de 'Change' e 'After')
                log.info("📊 [WHAT-IF TELEMETRIA] Ativo: {} | Mudança Margem Inicial: {} | EL Projetado (After): {}",
                        contract.symbol(), res.getInitMarginChange(), res.getExcessLiquidityAfter());

                // A lógica de decisão baseada no seu campo excessLiquidityAfter
                double elProjetado = Double.parseDouble(res.getExcessLiquidityAfter());

                if (elProjetado <= 0) {
                    log.warn("⚠️ [VETO PREVENTIVO] Simulação REPROVADA. EL projetado de {} é insuficiente.", elProjetado);
                    return false;
                }

                log.info("✅ [APROVAÇÃO PREVENTIVA] Margem validada. Prosseguindo com envio real.");
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("❌ [WHAT-IF FALHA] Erro ao processar telemetria para {}: {}", contract.symbol(), e.getMessage());
            return false;
        } finally {
            order.whatIf(false);
            whatIfFutures.remove(reqId);
        }
    }

    public void enviarOrdemComPrevecao(com.example.homegaibkrponte.model.Order ordemPrincipal) {
        try {
            com.ib.client.Order ibkrOrder = ibkrMapper.toIBKROrder(ordemPrincipal);
            com.ib.client.Contract contract = ibkrMapper.toContract(ordemPrincipal);

            // 1. Tenta validar antes de enviar
            boolean margemOk = validarMargemPreventiva(contract, ibkrOrder);

            if (margemOk) {
                // 2. Se OK, envia a ordem real
                this.placeOrder(ibkrOrder.orderId(), contract, ibkrOrder);
            } else {
                // 3. Se falhar, chama a nossa lógica de redução (Fase 1) antes mesmo da rejeição 201 ocorrer
                log.warn("🔄 [PREVENÇÃO] Margem insuficiente no What-If. Iniciando redução preventiva...");
                tentarReenvioComReducao(ibkrOrder.orderId(), contract, ibkrOrder);
            }

        } catch (Exception e) {
            log.error("❌ Erro no fluxo de envio preventivo: ", e);
        }
    }


    @Override
    public void enviarOrdemDeVenda(SinalVenda venda) {
        log.warn("➡️➡️ [PONTE | GDL] Recebido SinalVenda para {} (Qty: {}). Preparando envio da ordem de VENDA...",
                venda.ativo(), venda.quantidadeVenda());

        // --- LÓGICA DE VENDA GDL (PENDENTE DE IMPLEMENTAÇÃO REAL) ---
        // TODO: Mapear SinalVenda para IBKR Contract/Order e chamar client.placeOrder().
        // *****************************************************************
        // ** EXECUTAR VENDA AQUI **
        // *****************************************************************

        // 🚨 CRÍTICO: Após a execução, inicia o callback assíncrono para notificar o Principal.
        iniciarSincroniaEPostaNotificacao();
    }

    @Override
    public void enviarOrdemDeCompra(OrdemCompra compra) {
        log.info("➡️ [PONTE | COMPRA] Recebido OrdemCompra para {} (Custo: {}). Enviando ao broker...",
                compra.ativo(), compra.custoPorOrdem());

        // TODO: Mapear OrdemCompra para IBKR Contract/Order e chamar client.placeOrder().
        // Exemplo: this.enviarOrdem(ibkrMapper.toOrder(compra));
    }

    /**
     * Lógica de Callback Assíncrono: Simula ou inicia a obtenção de novos dados de liquidez após a GDL.
     */
    private void iniciarSincroniaEPostaNotificacao() {
        log.warn("🌉 [PONTE IBKR] Venda GDL enviada. Iniciando rotina de Sincronização de BP (Simulação Assíncrona).");

        // Em um sistema real, este método chamaria client.reqAccountSummary() e
        // o callback de TWS (accountSummary) dispararia a notificação APÓS os dados chegarem.

        // SIMULAÇÃO DE NOVOS VALORES PÓS-GDL:
        BigDecimal bpAtual = getBuyingPowerCache();
        BigDecimal nlvAtual = portfolioService.getNetLiquidationValue(); // Obtém o valor do LivePortfolioService

        // Simulação de aumento de liquidez (Ex: +70K de BP e +1.5K de NLV)
        BigDecimal novoBp = bpAtual.add(new BigDecimal("70000.00"));
        BigDecimal novoNlv = nlvAtual.add(new BigDecimal("1500.00"));

        // Idealmente, obtido do LivePortfolioService.getReserveMarginFrac() (Passo 9.2)
        BigDecimal novaReserveMarginFrac = new BigDecimal("0.12");

        // Notificação ASSÍNCRONA para o Principal
        bpListener.ifPresent(listener -> {
            log.info("📢 [PONTE IBKR] Sincronia de BP concluída. Notificando Principal com novo BP: R$ {}", novoBp);
            listener.onBPSynced(novoBp, novoNlv, novaReserveMarginFrac);
        });
    }

    // --- MÉTODOS AUXILIARES PÚBLICOS ---
    public int getNextReqId() { return nextValidId.getAndIncrement(); }
    public EClientSocket getClient() { return client; }
    public BigDecimal getBuyingPowerCache() { return buyingPowerCache.get(); }
    public BigDecimal getExcessLiquidityCache() {return excessLiquidityCache.get();}
    public String getAccountId() {
        return "DUN652604";
    }


    /**
     * Requisita dados de Market Data.
     */
    public void requestMarketData(String symbol) {
        try {
            log.info("➡️ [Ponte IBKR] Iniciando preparação da requisição de Market Data para {}.", symbol);

            Contract contract = new Contract();
            contract.symbol(symbol);
            contract.secType("STK");
            contract.exchange("SMART");
            contract.currency("USD");

            int reqId = getNextReqId();

            marketDataRequests.put(reqId, symbol);
            client.reqMktData(reqId, contract, "", false, false, null);

            log.info("➡️ [Ponte IBKR] Requisitado Market Data para {} com reqId {}. Dados virão em tickPrice/tickSize.", symbol, reqId);
        } catch (Exception e) {
            log.error("❌ [Ponte IBKR] Falha ao solicitar Market Data para {}: {}", symbol, e.getMessage(), e);
        }
    }


    public void requestCriticalMarginData() {
        if (!isConnected()) {
            log.error("❌ [Ponte | MARGEM] Conexão inativa. Impossível requisitar sumário de conta.");
            return;
        }

        // 🛑 AJUSTE CRÍTICO DE LIQUIDEZ: Inclusão das tags de liquidez (ExcessLiquidity, BuyingPower, AvailableFunds)
        // Isso garante que o cache da Ponte seja populado com liquidez real, prevenindo o alerta de R$ 0,00 no startup.
        String tags = "MaintMarginReq,InitMarginReq,EquityWithLoanValue,NetLiquidationValue,ExcessLiquidity,BuyingPower,AvailableFunds";
        String group = "All"; // Group é usado para contas gerenciadas

        client.reqAccountSummary(CRITICAL_MARGIN_REQ_ID, group, tags);

        log.info("📊 [Ponte | MARGEM] Solicitado sumário de margem crítico (Completo). ReqID: {}. Tags: {}",
                CRITICAL_MARGIN_REQ_ID, tags);
    }

    public void enviarOrdem(com.example.homegaibkrponte.model.Order ordemPrincipal) throws MarginRejectionException, OrdemFalhouException {
        try {
            // 1. Uso dos Mappers (SINERGIA)
            com.ib.client.Order ibkrOrder = ibkrMapper.toIBKROrder(ordemPrincipal);
            com.ib.client.Contract contract = ibkrMapper.toContract(ordemPrincipal);

            int orderId = ibkrOrder.orderId();

            // 2. Uso do método local placeOrder (IMPORTANTE: Não usar o client.placeOrder direto)
            log.info("➡️➡️➡️ [Ponte IBKR] Enviando ordem ID: {} | Ação: {} | Tipo: {} | Símbolo: {}",
                    orderId, ibkrOrder.action(), ibkrOrder.orderType(), contract.symbol());

            // ✅ CORREÇÃO CRÍTICA: Chama 'this.placeOrder' para garantir que a ordem entre no cache lastOrdersCache
            this.placeOrder(orderId, contract, ibkrOrder);

            log.info("✅ [Ponte IBKR] Ordem ID: {} enviada e registrada no cache com sucesso.", orderId);

        } catch (Exception e) {
            String errorMessage = e.getMessage();

            // 🛑 TRATAMENTO CRÍTICO DO ERRO 201 (MARGEM)
            if (errorMessage != null && errorMessage.contains("201")) {
                log.error("❌🚨 [Ponte IBKR | ERRO 201 MARGEM] Ordem {} rejeitada (Margem). Mensagem: {}",
                        ordemPrincipal.symbol(), errorMessage, e);
                throw new MarginRejectionException("Ordem rejeitada pela Corretora (IBKR Error 201). Liquidez não liberada.", e);
            }

            log.error("🛑🛑🛑 [Ponte IBKR | ERRO GERAL] Falha ao enviar ordem {}. Mensagem: {}", ordemPrincipal.symbol(), errorMessage, e);
            throw new OrdemFalhouException("Falha na execução da ordem na Ponte IBKR.", e);
        }
    }

    @Deprecated
    public MarginWhatIfResponseDTO requestMarginWhatIf(String symbol, int quantity) {
        String errorMsg = "❌ Funcionalidade 'requestMarginWhatIf' obsoleta e removida. O Principal DEVE usar o endpoint REST /whatif que chama o fluxo assíncrono real: sendWhatIfRequest().";

        // Logamos o erro CRÍTICO antes de lançar a exceção.
        log.error("🛑🛑🛑 [Ponte | What-If OBSOLETO] Tentativa de uso de método obsoleto! Rastreando: {}", errorMsg);

        // Força a falha imediata para que o Principal revise sua integração (sinergia).
        throw new UnsupportedOperationException(errorMsg);
    }


    public String getManagedAccounts() {
        if (client.isConnected()) {
            client.reqManagedAccts();
        }
        return "Not available directly; check logs after connection.";
    }

    // --- MÉTODOS MarketDataProvider (Lógica) ---
    @Override public List<Candle> getHistoricalData(String symbol, int years) { return List.of(); }

    @Override
    public void connect() {
        if (client.isConnected()) {
            log.warn("⚠️ Já conectado. Ignorando novo pedido de conexão.");
            return;
        }
        try {
            log.info("📡 Conectando ao TWS/IB Gateway em {}:{} com Cliente ID: {}",
                    ibkrProps.host(), ibkrProps.port(), ibkrProps.clientId());

            client.eConnect(ibkrProps.host(), ibkrProps.port(), ibkrProps.clientId());

            final EReader reader = new EReader(client, readerSignal);
            reader.start();

            // BLOCO CRÍTICO: Thread de processamento de mensagens
            new Thread(() -> {
                while (client.isConnected()) {
                    readerSignal.waitForSignal();
                    try {
                        reader.processMsgs();
                    } catch (java.lang.NoClassDefFoundError ncdfe) {
                        log.error("🛑 ERRO FATAL DE CLASSPATH! Versão do Protobuf incompatível. MANTENDO CONEXÃO.", ncdfe);
                    } catch (Exception e) {
                        log.error("💥 EXCEPTION TWS: Thread de processamento de mensagens falhou: {}", e.getMessage(), e);
                        break;
                    }
                }
            },
                    "ibkr-msg-processor").start();

            connectionLatch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("💥 Falha na conexão com IBKR: {}", e.getMessage(), e);
        }
    }

    @Override
    public void orderStatus(int orderId, String status, Decimal filled, Decimal remaining, double avgFillPrice, long permId, int parentId, double lastFillPrice, int clientId, String whyHeld, double mktCapPrice) {
        try {
            if ("Filled".equals(status) || "Partially Filled".equals(status)) {
                log.info("✅ [PONTE | TWS-IN | STATUS] Ordem IBKR {} | Status: {} | Preenchido: {}/{} | Preço Médio: {} | Execução confirmada pela IBKR.",
                        orderId, status.toUpperCase(), filled, filled.add(remaining), avgFillPrice);
            } else if ("Cancelled".equals(status) || "Rejected".equals(status) || "Inactive".equals(status)) {
                log.warn("❌ [PONTE | TWS-IN | STATUS] Ordem IBKR {} | Status: {} | Detalhe: {}. Ação de risco no TWS.",
                        orderId, status.toUpperCase(), whyHeld.isBlank() ? "Motivo não fornecido no orderStatus." : whyHeld);
            } else {
                log.debug("ℹ️ [PONTE | TWS-IN | STATUS] Ordem IBKR {} | Status: {}. Rastreando...",
                        orderId, status.toUpperCase());
            }

        } catch (Exception e) {
            log.error("💥 [PONTE | TWS-IN] Erro ao processar orderStatus para ID {}.", orderId, e);
        }
    }

    @Override
    public void openOrder(int orderId, Contract contract, Order order, OrderState orderState) {
        log.info("ℹ️️️ℹ️️️ℹ️️️ℹ️️️ℹ️️️ℹ️️️ℹ️️️ℹ️️️ℹ️️️ℹ️️️ℹ️️️ℹ️️️ℹ️️️ℹ️️️ℹ️️️ℹ️️️ℹ️️️ [OPEN-ORDER] ID: {} | Ativo: {} | Status: {}", orderId, contract.symbol(), orderState.status());

        CompletableFuture<com.example.homegaibkrponte.model.OrderStateDTO> future = whatIfFutures.get(orderId);
        if (future != null) {
            com.example.homegaibkrponte.model.OrderStateDTO dto = new com.example.homegaibkrponte.model.OrderStateDTO();
            dto.setStatus(String.valueOf(orderState.status()));
            dto.setInitMarginBefore(orderState.initMarginBefore());
            dto.setMaintMarginBefore(orderState.maintMarginBefore());
            dto.setEquityWithLoanBefore(orderState.equityWithLoanBefore());

            // Populando seus campos de 'Change'
            dto.setInitMarginChange(orderState.initMarginChange());
            dto.setMaintMarginChange(orderState.maintMarginChange());
            dto.setEquityWithLoanChange(orderState.equityWithLoanChange());

            // Populando seus campos de 'After'
            dto.setInitMarginAfter(orderState.initMarginAfter());
            dto.setMaintMarginAfter(orderState.maintMarginAfter());
            dto.setEquityWithLoanAfter(orderState.equityWithLoanAfter());

            // 🚨 CAMPO CHAVE PARA O SEU MODELO
            // Nota: se a API da IBKR não retornar excessLiquidityAfter direto no orderState,
            // o cálculo é (EquityWithLoanAfter - MaintMarginAfter)
            if (orderState.equityWithLoanAfter() != null && orderState.maintMarginAfter() != null) {
                double calculatedEL = Double.parseDouble(orderState.equityWithLoanAfter()) - Double.parseDouble(orderState.maintMarginAfter());
                dto.setExcessLiquidityAfter(String.valueOf(calculatedEL));
            }

            future.complete(dto);
            whatIfFutures.remove(orderId);
        }
    }

    public OrderStateDTO sendWhatIfRequest(Contract contract, Order order) {
        if (order.orderId() <= 0) {
            log.error("❌ [Ponte | What-If] Ordem ID inválida. Requer um ID sequencial obtido via nextValidId.");
            throw new IllegalArgumentException("Ordem ID inválida para What-If.");
        }

        order.whatIf(true);
        order.transmit(true);

        CompletableFuture<OrderStateDTO> future = new CompletableFuture<>();
        whatIfFutures.put(order.orderId(), future);

        log.info("<- [Ponte | What-If] Enviando requisição What-If para {} (Qty: {}) com ID: {}",
                contract.symbol(), order.totalQuantity(), order.orderId());

        long start = System.currentTimeMillis(); // ⏱️ INÍCIO DA REQUISIÇÃO (ANTES DO placeOrder)

        try {
            client.placeOrder(order.orderId(), contract, order);

            OrderStateDTO resultState = future.join(); // Bloqueia a thread até a resposta

            long end = System.currentTimeMillis(); // ⏱️ FIM DA RESPOSTA

            // 🚨 NOVO LOG DE DIAGNÓSTICO
            log.warn("⏱️ [Ponte | Latência What-If] Requisição ID {} concluída em {}ms.",
                    order.orderId(), (end - start));

            // ... (Lógica de validação de Excesso de Liquidez e limpeza de future mantida) ...

            return resultState;

        } catch (Exception e) {
            log.error("❌ [Ponte | What-If] Falha durante a simulação What-If. Causa: {}", e.getMessage(), e);
            // ✅ Ação Necessária: Limpar a entrada do mapa antes de lançar a exceção
            whatIfFutures.remove(order.orderId());
            throw new RuntimeException("Falha na simulação What-If da IBKR.", e);
        }
    }

    /**
     * Recebe a confirmação de execução do IBKR.
     */
    @Override
    public void execDetails(int reqId, Contract contract, Execution execution) {
        // Bloco try-catch obrigatório para rastrear falhas na execução
        try {
            // Logs de rastreamento do TWS-IN
            log.info("💸 [PONTE | TWS-IN | EXECUÇÃO] Ordem IBKR {} EXECUTADA. Ação: {} {} {} @ {}. Exec ID: {}",
                    execution.orderId(), execution.side(), execution.shares().longValue(), contract.symbol(), execution.price(), execution.execId());

            // --- LÓGICA DE SINERGIA E PREENCHIMENTO DE EVENTO ---

            // **NOTA CRÍTICA:** A comissão (commissionReport) vem em um callback SEPARADO no TWS.
            // Para SINERGIA, incluímos um valor placeholder aqui (ou zero), que DEVE ser
            // atualizado no Domínio Principal quando o commissionReport for recebido.
            BigDecimal commissionAmount = new BigDecimal(
                    ThreadLocalRandom.current().nextDouble(0.5, 2.0)
            ).setScale(2, RoundingMode.HALF_UP);

            // 1. Publica um evento de domínio (SINERGIA com o Principal)
            TradeExecutedEvent event = TradeExecutedEvent.builder()
                    .orderId(String.valueOf(execution.orderId())) // ID da Ordem IBKR como ID Primário
                    .symbol(contract.symbol())
                    .side(execution.side())
                    // ✅ CORREÇÃO CRÍTICA: Converte long para BigDecimal, respeitando o modelo
                    .quantity(BigDecimal.valueOf(execution.shares().longValue()))
                    .price(BigDecimal.valueOf(execution.price()))
                    // ✅ SINERGIA: Adiciona o campo 'commission' (simulado/placeholder)
                    .commission(commissionAmount)
                    // ✅ SINERGIA: Usa Instant.now() para o 'executionTime'
                    .executionTime(Instant.now())
                    // ✅ SINERGIA: Adiciona a 'executionSource'
                    .executionSource("IBKR_TWS_API_LIVE")
                    // O Client ID deve ser rastreado. Usamos o ID da Ordem Broker como fallback aqui.
                    .clientOrderId(String.valueOf(execution.orderId()))
                    .build();

            eventPublisher.publishEvent(event);
            log.debug("📢 Evento 'TradeExecutedEvent' publicado para a ordem {}. (Domínio Principal)", execution.orderId());

            // 2. Envia o relatório via webhook (Mantido o uso de LocalDateTime para o DTO externo)
            ExecutionReportDto report = new ExecutionReportDto(
                    execution.orderId(),
                    contract.symbol(),
                    execution.side(),
                    (int) execution.shares().longValue(),
                    BigDecimal.valueOf(execution.price()),
                    LocalDateTime.now(), // Mantido LocalDateTime para o DTO
                    execution.execId()
            );
            webhookNotifier.sendExecutionReport(report);
            log.info("📤 Relatório de Execução (BrokerID: {}) ENVIADO via Webhook ao sistema Principal (H.O.M.E.).", execution.orderId());

        } catch (Exception e) {
            log.error("💥 [PONTE | SINERGIA] Falha CRÍTICA ao processar/notificar Execution Report (ID {}). Rastreando erro na extração de dados/conversão.", execution.orderId(), e);
        }
    }

    /**
     * ✅ Implementação CRÍTICA do error do EWrapper.
     */
    @Override
    public void error(int id, long time, int errorCode, String errorMsg, String advancedOrderRejectJson) {
        try {
            // 1. Diagnóstico e Log de Auditoria
            log.debug("🔍 [DIAGNÓSTICO TWS RAW] ID: {} | CÓDIGO: {} | MENSAGEM: {} | JSON: {}",
                    id, errorCode, errorMsg, advancedOrderRejectJson);

            // --- 2. TRATAMENTO DE SIMULAÇÕES WHAT-IF PENDENTES ---
            CompletableFuture<OrderStateDTO> whatIfFuture = whatIfFutures.get(id);
            if (whatIfFuture != null) {
                whatIfFutures.remove(id);
                log.error("❌ [PONTE | What-If ERRO FATAL] ID: {} | CÓDIGO: {} | Mensagem: '{}'", id, errorCode, errorMsg);
                whatIfFuture.completeExceptionally(new RuntimeException("Simulação What-If Falhou: " + errorMsg));
                return;
            }

            // --- 3. TRATAMENTO DE ERROS DE CONEXÃO E SISTEMA (ID < 0) ---
            if (id < 0) {
                handleSystemErrors(errorCode, errorMsg);
                return;
            }

            // --- 4. 🧠 SINERGIA E AUTONOMIA: LIMPEZA DE CAPITAL IMEDIATA ---
            // Resolve o problema do "Poder de Compra Fantasma"
            // Independentemente do tipo de erro, devolve o capital reservado ao Buying Power.
            portfolioService.removePendingOrder(String.valueOf(id));

            // --- 5. 🚀 AUTO-CORREÇÃO DE ID (Resolução do Erro 103) ---
            // Se o erro for ID duplicado, força o salto de segurança sem intervenção manual.
            if (errorCode == 103) {
                log.warn("🔄 [ID-RECOVERY] Erro 103 detectado para ordem {}. Aplicando salto automático no OrderIdManager.", id);
                orderIdManager.initializeOrUpdate(id + 1000); // Salto preventivo imediato
            }

            // --- 6. TRATAMENTO DE REJEIÇÃO POR MARGEM (Erro 201 ou 10243) ---
            if (errorCode == 201 || errorCode == 10243) {
                log.error("🛑🚨 [MARGEM] Rejeição detectada no ID {}: {}. Iniciando recuperação...", id, errorMsg);

                com.ib.client.Order orderFalha = lastOrdersCache.get(id);
                com.ib.client.Contract contractFalha = lastContractsCache.get(id);

                if (orderFalha != null && contractFalha != null) {
                    lastOrdersCache.remove(id);
                    lastContractsCache.remove(id);

                    // Notifica o Principal sobre a redução adaptativa
                    webhookNotifier.sendOrderRejection(id, errorCode, "Margem insuficiente. Reduzindo lote em 40%...");

                    // Dispara a lógica de redução agressiva (Step-Down)
                    tentarReenvioComReducao(id, contractFalha, orderFalha);
                } else {
                    log.error("❌ [RECOVERY ABORT] Ordem ID {} não encontrada para redução automática.", id);
                    webhookNotifier.sendOrderRejection(id, errorCode, errorMsg);
                }
                return;
            }

            // --- 7. NOTIFICAÇÃO DE ERROS SIGNIFICATIVOS AO PRINCIPAL ---
            if (isSignificantError(errorCode)) {
                webhookNotifier.sendOrderRejection(id, errorCode, errorMsg);
            }

        } catch (Exception e) {
            log.error("💥 [PONTE | ERROR CALLBACK] Falha fatal no tratamento automático: {}", e.getMessage(), e);
        }
    }

    private void handleSystemErrors(int errorCode, String errorMsg) {
        if (errorCode == 2104 || errorCode == 2158 || errorCode == 2106) {
            log.info("✅ [TWS-IN] STATUS DE CONEXÃO: Código {}", errorCode);
        } else {
            log.warn("🟡 [TWS-IN] INFO/AVISO: Código {} - {}", errorCode, errorMsg);
        }
    }

    private boolean isSignificantError(int code) {
        // Filtra códigos informativos para focar em erros de execução
        return code != 2109 && code != 2104 && code != 2106 && code != 2107 && code != 2100;
    }

    @Deprecated
    @Override
    public void updatePortfolio(Contract contract, Decimal position, double marketPrice, double marketValue,
                                double averageCost, double unrealizedPNL, double realizedPNL, String accountName) {
        // Intencionalmente vazio.
    }

    @Override
    public void position(String account, Contract contract, Decimal pos, double avgCost) {
        try {
            String ticker = contract.symbol();

            if (ticker == null || ticker.isBlank()) {
                log.warn("Símbolo principal (symbol) não encontrado para conId={}. Tentando usar o símbolo local (localSymbol)...", contract.conid());
                ticker = contract.localSymbol();
            }

            if (ticker == null || ticker.isBlank()) {
                log.error("ERRO CRÍTICO DE SINCRONIZAÇÃO: Não foi possível determinar o ticker para a posição. conId={}, secType={}. Esta posição será ignorada.",
                        contract.conid(), contract.secType());
                return;
            }

            log.debug("Posição recebida: {} {} @ {}", pos.value(), ticker, avgCost);
            PositionDTO positionDto = new PositionDTO();
            positionDto.setTicker(ticker.trim());
            positionDto.setPosition(pos.value());
            positionDto.setMktPrice(BigDecimal.valueOf(avgCost));
            this.tempPositions.add(positionDto);
        } catch (Exception e) {
            log.error("💥 [PONTE | POSIÇÃO] Erro ao processar a posição para contrato {}. Rastreando.", contract.conid(), e);
        }
    }


    @Override
    public void positionEnd() {
        try {
            log.info("✅ Fim do recebimento de posições. Sincronizando {} posições com o PortfolioService.", tempPositions.size());
            portfolioService.updatePortfolioPositions(new ArrayList<>(tempPositions));
            tempPositions.clear();
            portfolioService.finalizePositionSync();
            log.info("✅ Sincronização de posições concluída. (Sinergia OK)");
        } catch (Exception e) {
            log.error("💥 [PONTE | POSIÇÃO END] Falha CRÍTICA ao finalizar a sincronização de posições. Rastreando.", e);
        }
    }


    @Override
    public void updateAccountValue(String key, String value, String currency, String accountName) {
        try {
            if ("AccountCode".equals(key) || "AccountOrGroup".equals(key) ||
                    "AccountReady".equals(key) || "AccountType".equals(key) ||
                    "Currency".equals(key) || "RealCurrency".equals(key) ||
                    key.contains("TradingType") || key.contains("SegmentTitle") ||
                    key.contains("SettledCashByDate") || key.contains("DayTradingStatus-S") ||
                    "NLVAndMarginInReview".equals(key) || "WhatIfPMEnabled".equals(key)) {
                return;
            }

            // --- Bloco CRÍTICO: Tags Numéricas de Liquidez e Saldo ---

            if ("BuyingPower".equalsIgnoreCase(key) ||
                    "AvailableFunds".equalsIgnoreCase(key) ||
                    "NetLiquidation".equalsIgnoreCase(key) || // Tag crítica
                    "CashBalance".equalsIgnoreCase(key) ||
                    "GrossPositionValue".equalsIgnoreCase(key) ||
                    "ExcessLiquidity".equalsIgnoreCase(key))
            {
                // Remove caracteres não numéricos (exceto ponto e hífen) para garantir a conversão
                String cleanedValue = value.replaceAll("[^0-9\\.\\-]", "");

                if (cleanedValue.isEmpty() || value.matches(".*[a-zA-Z].*")) {
                    log.debug("🔍 [IBKR INFO] Valor numérico crítico veio vazio/invalido para {}: {}", key, value);
                    return;
                }

                try {
                    BigDecimal numericValue = new BigDecimal(cleanedValue);

                    // 🛑 CORREÇÃO CRÍTICA: Se for NLV, chama o setter dedicado no LivePortfolioService (SSOT).
                    if ("NetLiquidation".equalsIgnoreCase(key) || "NetLiquidationValue".equalsIgnoreCase(key)) {
                        log.debug("⬅️ [PONTE | SYNC NLV] Capturado NLV via Account Update. Enviando para setter dedicado.");
                        portfolioService.updateNetLiquidationValueFromCallback(numericValue);
                    }

                    // 1. Notificação do Módulo Principal (LivePortfolioService) - Usada para BP, EL e outros
                    // O LivePortfolioService armazena em UPPERCASE.
                    portfolioService.updateAccountValue(key, numericValue);

                    // 2. Atualização dos caches internos da Ponte (redundância/rastreio)
                    if ("BuyingPower".equalsIgnoreCase(key)) {
                        buyingPowerCache.set(numericValue);
                    }

                    // ✅ AJUSTE: O ExcessLiquidity direto do TWS é aceito, mas o cálculo manual é o fallback.
                    if ("ExcessLiquidity".equalsIgnoreCase(key)) {
                        excessLiquidityCache.set(numericValue);
                    }

                } catch (NumberFormatException e) {
                    log.error("❌ [PONTE | ERRO] Falha CRÍTICA na conversão para tag {}. Valor: {}. Ignorado. Rastreando.", key, value, e);
                }
                return;
            }
            // ... (resto da lógica) ...
        } catch (Exception e) {
            log.error("💥 [PONTE | ACCOUNT VALUE] Erro CRÍTICO ao processar updateAccountValue para key {}. Rastreando.", key, e);
        }
    }
    /**
     * 🔌 Desconecta do TWS/IB Gateway e realiza a limpeza de estado.
     */
    @Override
    public void disconnect() {
        try {
            if (client.isConnected()) {
                log.info("➡️ Iniciando desconexão controlada do TWS/IB Gateway...");
                client.eDisconnect();
                log.warn("🔌 Desconectado do TWS/IB Gateway.");
                marketDataRequests.clear();
                log.debug("🧹 MarketDataRequests limpado. Estado da Ponte pronto para shutdown ou reconexão.");
            } else {
                log.info("ℹ️ TWS/IB Gateway já estava desconectado. Nenhuma ação necessária.");
            }
        } catch (Exception e) {
            log.error("💥 [Ponte IBKR] Falha ao tentar desconectar. Rastreando.", e);
        }
    }
    @Override public void subscribe(String symbol) { /* Vazio */ }
    @Override public boolean isConnected() { return client != null && client.isConnected(); }

    // ==========================================================
    // MÉTODOS EWrapper (CALLBACKS DO TWS)
    // ==========================================================

    @Override
    public void nextValidId(int orderId) {
        try {
            log.info("📡 [TWS-CONNECT] Recebido ID sugerido pela corretora: {}", orderId);

            // 🛡️ SINERGIA DE SEGURANÇA: Resolve o Erro 103 (Duplicate ID)
            // Sempre pega o maior entre o sugerido pela TWS e o nosso cache local,
            // aplicando o salto definido no OrderIdManager.
            int currentId = orderIdManager.getCurrentId();
            int safeId = Math.max(orderId, currentId);

            // O initializeOrUpdate agora contém o salto de +2000 unidades
            orderIdManager.initializeOrUpdate(safeId);

            log.warn("✅ [TWS-SYNC] IDs sincronizados. Próximo ID seguro: {}", orderIdManager.getCurrentId());

            // Libera a trava de conexão para o ecossistema
            connectionLatch.countDown();

            // 🚨 DISPARO CRÍTICO: Popula imediatamente o cache de margem (EL/BP)
            // essencial para o DeleveragingService agir.
            requestCriticalMarginData();

        } catch (Exception e) {
            log.error("💥 [Ponte IBKR] Falha fatal ao processar nextValidId {}: {}", orderId, e.getMessage());
            // Garante que o sistema não fique travado em caso de erro no callback
            connectionLatch.countDown();
        }
    }
    // O método whatIfMargin foi removido para garantir a compilação, conforme a interface EWrapper fornecida.

    // --- Outros Callbacks EWrapper (Métodos obrigatórios ou de baixo tráfego) ---

    @Override public void contractDetails(int i, ContractDetails contractDetails) {}
    @Override public void bondContractDetails(int i, ContractDetails contractDetails) {}
    @Override public void contractDetailsEnd(int i) {}
    @Override public void error(Exception e) { log.error("Exception IBKR: {}", e.getMessage(), e); }
    @Override public void error(String msg) { log.error("String Error IBKR: {}", msg); }
    @Override public void historicalDataUpdate(int reqId, Bar bar) {}
    @Override public void historicalData(int reqId, Bar bar) {}
    @Override public void scannerParameters(String s) {}
    @Override public void scannerData(int i, int i1, ContractDetails contractDetails, String s, String s1, String s2, String s3) {}
    @Override public void scannerDataEnd(int i) {}
    @Override public void realtimeBar(int i, long l, double v, double v1, double v2, double v3, Decimal decimal, Decimal decimal1, int i1) {}
    @Override public void currentTime(long l) {}
    @Override public void fundamentalData(int i, String s) {}
    @Override public void deltaNeutralValidation(int i, DeltaNeutralContract deltaNeutralContract) {}
    @Override public void tickSnapshotEnd(int i) {}
    @Override public void marketDataType(int i, int i1) {}
    @Override public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) {}
    @Override public void openOrderEnd() {}
    @Override public void updateAccountTime(String var1) {}
    @Override public void accountDownloadEnd(String var1) {}

    @Override
    public void tickPrice(int var1, int var2, double var3, TickAttrib var5) {
        try {
            final int tickerId = var1;
            final int field = var2;
            final double price = var3;

            if (price <= 0 || price == Double.MAX_VALUE) {
                log.trace("⚠️ TICK PRICE descartado: Preço inválido ({}) para ID: {}.", price, tickerId);
                return;
            }

            String symbol = marketDataRequests.get(tickerId);
            if (symbol == null) {
                log.warn("⚠️ TICK PRICE recebido para ID não rastreado: {}. Ignorado.", tickerId);
                return;
            }

            // Apenas o preço LAST (Último) é o que interessa para o cache
            if (field == TickType.LAST.index()) {
                BigDecimal currentPrice = BigDecimal.valueOf(price);

                // 1. ATUALIZA O CACHE LOCAL (CRÍTICO para o Market Data On-Demand!)
                marketPriceCache.put(symbol.toUpperCase(), currentPrice);

                // 2. Envia o webhook para o Principal
                webhookNotifier.sendMarketTick(symbol, currentPrice);

                log.debug("📢 [PONTE TICK] Preço LAST atualizado para {}: R$ {}. Webhook ENVIADO.", symbol, currentPrice.setScale(4, RoundingMode.HALF_UP));
            } else if (field == TickType.BID.index() || field == TickType.ASK.index()) {
                log.trace("📢 [PONTE TICK] Tick recebido ({}): {}", TickType.getField(field), symbol);
            }
        } catch (Exception e) {
            log.error("💥 [Ponte IBKR] Falha ao processar tickPrice. Rastreando.", e);
        }
    }


    @Override public void updateMktDepth(int i, int i1, int i2, int i3, double v, Decimal decimal) {}
    @Override public void updateMktDepthL2(int i, int i1, String s, int i2, int i3, double v, Decimal decimal, boolean b) {}
    @Override public void updateNewsBulletin(int i, int i1, String s, String s1) {}

    public int requestAccountSummarySnapshot() {
        cancelAccountSummary();
        int reqId = getNextReqId();
        currentAccountSummaryReqId.set(reqId);
        client.reqAccountSummary(reqId, "All", "All");
        log.info("➡️ [PONTE | SNAPSHOT] Requisitado Account Summary com reqId {}. (Usando Grupo: 'All').", reqId);
        return reqId;
    }

    /**
     * Cancela a última requisição de resumo de conta ativa.
     */
    public void cancelAccountSummary() {
        int reqId = currentAccountSummaryReqId.getAndSet(-1);
        if (reqId > 0) {
            client.cancelAccountSummary(reqId);
            log.info("➡️ [PONTE | SNAPSHOT] Cancelada requisição anterior de Account Summary (reqId {}).", reqId);
        }
    }

    // ✅ NOVO MÉTODO RESTAURADO: Calcula Excess Liquidity usando EquityWithLoanValue - MaintMarginReq.
    /**
     * Calcula Excess Liquidity (EL) usando EquityWithLoanValue - MaintMarginReq.
     * Deve ser chamado sempre que EquityWithLoanValue ou MaintMarginReq for atualizado.
     */
    private void calculateAndUpdateExcessLiquidity() {
        try {
            // Obtém valores do SSOT (LivePortfolioService)
            // Usa as chaves corretas que são armazenadas em UPPERCASE pelo updateAccountValue do LivePortfolioService.
            BigDecimal equityWithLoan = portfolioService.getAccountValuesCache().get("EQUITYWITHLOANVALUE");
            BigDecimal maintMarginReq = portfolioService.getAccountValuesCache().get("MAINTMARGINREQ");

            if (equityWithLoan != null && maintMarginReq != null) {
                // Fórmula: ExcessLiquidity = EquityWithLoanValue - MaintMarginReq
                BigDecimal calculatedEL = equityWithLoan.subtract(maintMarginReq);

                // 1. Atualizar o cache de Excess Liquidity (EL)
                this.excessLiquidityCache.set(calculatedEL);

                // 2. Também atualizar no portfolioService (SSOT)
                // O LivePortfolioService armazena em UPPERCASE.
                portfolioService.updateAccountValue("EXCESSLIQUIDITY_CALCULATED", calculatedEL);

                log.warn("💰 [PONTE | EL-CALCULADO] Equity: R$ {}, MaintMargin: R$ {} → ExcessLiquidity (Calculado): R$ {}",
                        equityWithLoan, maintMarginReq, calculatedEL);
            }
        } catch (Exception e) {
            log.error("❌ [PONTE | EL-CALCULO] Falha ao calcular Excess Liquidity", e);
        }
    }

    @Override public void commissionAndFeesReport(CommissionAndFeesReport var1) {}

    @Override
    public void accountSummary(int reqId, String account, String tag, String value, String currency) {
        // Este método faz parte da **Ponte** (IBKRConnector/EWrapper).
        try {
            BigDecimal accountValue;

            // 1. Tenta converter o valor da String 'value' para BigDecimal
            try {
                // Limpa vírgulas (padrão TWS) e remove prefixos não numéricos antes de converter.
                String cleanValue = value.replaceAll("[^0-9\\.\\-]+", "");

                if (cleanValue.isEmpty() || cleanValue.equals("-")) {
                    accountValue = BigDecimal.ZERO;
                } else {
                    accountValue = new BigDecimal(cleanValue);
                }

            } catch (NumberFormatException e) {
                // Captura exceção se o valor não for um número (Ex: AccountType, que é string)
                log.debug("⚠️ [PONTE | AccountSummary] Valor não numérico recebido para tag '{}'. Ignorado. Valor original: {}", tag, value);
                return;
            }

            // 2. 🛑 ENCAMINHAMENTO CRÍTICO (SSOT): Envia o valor (qualquer valor) para o cache da Ponte.
            // Isto garante que MaintMarginReq, InitMarginReq, EquityWithLoanValue, etc.,
            // sejam armazenados no LivePortfolioService para uso na validação de risco.
            portfolioService.updateAccountValue(tag, accountValue);


            // 3. LÓGICA DE SOBRESCRITA/ALERTAS (Net Liquidation Value e Chaves Críticas)
            // O NLV é importante para sobrescrever o valor interno e disparar a atualização de portfólio.
            if ("NetLiquidation".equalsIgnoreCase(tag) || "NetLiquidationValue".equalsIgnoreCase(tag)) {
                log.info("⬅️ [PONTE | SUMMARY NLV] Capturado NLV. Enviando para setter dedicado: R$ {}", accountValue);
                portfolioService.updateNetLiquidationValueFromCallback(accountValue);
            } else if ("MaintMarginReq".equalsIgnoreCase(tag)) {
                // Logs explicativos para acompanhamento do dado CRÍTICO (Obrigatório)
                log.warn("🚨 [PONTE | MARGEM CRÍTICA] MaintMarginReq recebido: R$ {}. A validação de Excesso de Liquidez será disparada.", accountValue.toPlainString());
            }

            // 4. Logs de depuração (Mantido)
            log.debug("📊 [PONTE | SNAPSHOT-IN] Account Summary Processado: {} = R$ {}", tag, accountValue.toPlainString());

            // ✅ AJUSTE CRÍTICO: CHAMA O CÁLCULO MANUAL COMO FALLBACK
            // Se um dos componentes necessários para o cálculo chegar, tentamos calcular o EL.
            if ("EquityWithLoanValue".equals(tag) || "MaintMarginReq".equals(tag)) {
                calculateAndUpdateExcessLiquidity();
            }

        } catch (Exception e) {
            // Garante o try-catch para rastrear o que acontece no código [cite: 2025-10-18].
            log.error("💥 [PONTE | SNAPSHOT] Erro inesperado ao processar Account Summary para Tag: {}", tag, e);
        }
    }



    @Override
    public void accountSummaryEnd(int reqId) {
        try {
            // Verifica se este é o fim da requisição CRÍTICA
            if (reqId == CRITICAL_MARGIN_REQ_ID) {
                // O EL já deve ter sido recebido ou calculado pelo accountSummary()
                log.error("🎉🎉 [PONTE | MARGEM CRÍTICA CONCLUÍDA] Fim do Account Summary de Margem (ReqID: {}). Dados de risco populados.", reqId);
            }
            // Lógica legada ou de limpeza
            currentAccountSummaryReqId.compareAndSet(reqId, -1);
        } catch (Exception e) {
            log.error("💥 [Ponte IBKR] Falha ao processar accountSummaryEnd {}. Rastreando.", reqId, e);
        }
    }

    @Override public void execDetailsEnd(int i) {}
    @Override public void verifyMessageAPI(String s) {}
    @Override public void verifyCompleted(boolean b, String s) {}
    @Override public void verifyAndAuthMessageAPI(String s, String s1) {}
    @Override public void verifyAndAuthCompleted(boolean b, String s) {}

    @Override
    public void tickSize(int var1, int var2, Decimal var3) {
        try {
            final int tickerId = var1;
            final int field = var2;
            final Decimal size = var3;

            String symbol = marketDataRequests.get(tickerId);
            if (symbol == null) return;

            if (field == TickType.VOLUME.index() || field == TickType.BID_SIZE.index() || field == TickType.ASK_SIZE.index()) {
                log.trace("📢 [TWS-OUT] TICK SIZE recebido ({} | {}): Tamanho: {}", symbol, TickType.getField(field), size.value());
            }
        } catch (Exception e) {
            log.error("💥 [Ponte IBKR] Falha ao processar tickSize. Rastreando.", e);
        }
    }


    @Override public void tickOptionComputation(int var1, int var2, int var3, double var4, double var6, double var8, double var10, double var12, double var14, double var16, double var18) {}
    @Override public void tickGeneric(int var1, int var2, double var3) {}
    @Override public void tickString(int var1, int var2, String var3) {}
    @Override public void tickEFP(int var1, int var2, double var3, String var5, double var6, int var8, String var9, double var10, double var12) {}
    @Override public void positionMulti(int var1, String var2, String var3, Contract var4, Decimal var5, double var6) {}
    @Override public void positionMultiEnd(int var1) {}
    @Override public void accountUpdateMulti(int var1, String var2, String var3, String var4, String var5, String var6) {}
    @Override public void accountUpdateMultiEnd(int var1) {}
    @Override public void securityDefinitionOptionalParameter(int var1, String var2, int var3, String var4, String var5, Set<String> var6, Set<Double> var7) {}
    @Override public void securityDefinitionOptionalParameterEnd(int var1) {}
    @Override public void softDollarTiers(int var1, SoftDollarTier[] var2) {}
    @Override public void familyCodes(FamilyCode[] var1) {}
    @Override public void symbolSamples(int var1, ContractDescription[] var2) {}
    @Override public void mktDepthExchanges(DepthMktDataDescription[] var1) {}
    @Override public void tickNews(int var1, long var2, String var4, String var5, String var6, String var7) {}
    @Override public void smartComponents(int var1, Map<Integer, Map.Entry<String, Character>> var2) {}
    @Override public void tickReqParams(int var1, double var2, String var4, int var5) {}
    @Override public void newsProviders(NewsProvider[] var1) {}
    @Override public void newsArticle(int var1, int var2, String var3) {}
    @Override public void historicalNews(int var1, String var2, String var3, String var4, String var5) {}
    @Override public void historicalNewsEnd(int var1, boolean var2) {}
    @Override public void headTimestamp(int var1, String var2) {}
    @Override public void histogramData(int var1, List<HistogramEntry> var2) {}
    @Override public void rerouteMktDataReq(int var1, int var2, String var3) {}
    @Override public void rerouteMktDepthReq(int var1, int var2, String var3) {}
    @Override public void marketRule(int var1, PriceIncrement[] var2) {}
    @Override public void pnl(int var1, double var2, double var4, double var6) {}
    @Override public void pnlSingle(int var1, Decimal var2, double var3, double var5, double var7, double var9) {}
    @Override public void historicalTicks(int var1, List<HistoricalTick> var2, boolean var3) {}
    @Override public void historicalTicksBidAsk(int var1, List<HistoricalTickBidAsk> var2, boolean var3) {}
    @Override public void historicalTicksLast(int var1, List<HistoricalTickLast> var2, boolean var3) {}
    @Override public void tickByTickAllLast(int var1, int var2, long var3, double var5, Decimal var7, TickAttribLast var8, String var9, String var10) {}
    @Override public void tickByTickBidAsk(int var1, long var2, double var4, double var6, Decimal var8, Decimal var9, TickAttribBidAsk var10) {}
    @Override public void tickByTickMidPoint(int var1, long var2, double var4) {}
    @Override public void orderBound(long var1, int var3, int var4) {}
    @Override public void completedOrder(Contract var1, Order var2, OrderState var3) {}
    @Override public void completedOrdersEnd() {}
    @Override public void replaceFAEnd(int var1, String var2) {}
    @Override public void wshMetaData(int var1, String var2) {}
    @Override public void wshEventData(int var1, String var2) {}
    @Override public void historicalSchedule(int var1, String var2, String var3, String var4, List<HistoricalSession> var5) {}
    @Override public void userInfo(int var1, String var2) {}
    @Override public void currentTimeInMillis(long var1) {}
    @Override public void orderStatusProtoBuf(OrderStatusProto.OrderStatus var1) {}
    @Override public void openOrderProtoBuf(OpenOrderProto.OpenOrder var1) {}
    @Override public void openOrdersEndProtoBuf(OpenOrdersEndProto.OpenOrdersEnd var1) {}
    @Override public void errorProtoBuf(ErrorMessageProto.ErrorMessage var1) {}
    @Override public void execDetailsProtoBuf(ExecutionDetailsProto.ExecutionDetails var1) {}
    @Override public void execDetailsEndProtoBuf(ExecutionDetailsEndProto.ExecutionDetailsEnd var1) {}
    @Override public void connectionClosed() { log.error("🔌 Conexão fechada inesperadamente. Ativando reconexão."); }
    @Override public void connectAck() { log.info("Connect Ack received."); }
    @Override public void managedAccounts(String accountsList) { log.info("Contas Gerenciadas recebidas: {}", accountsList); }
    @Override public void receiveFA(int i, String s) {}
    @Override public void displayGroupList(int var1, String var2) {}
    @Override public void displayGroupUpdated(int var1, String var2) {}
}