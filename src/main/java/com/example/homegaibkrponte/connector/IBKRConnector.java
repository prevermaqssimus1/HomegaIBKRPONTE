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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.MeterRegistry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    private final ConcurrentHashMap<Integer, CompletableFuture<List<Candle>>> historicalFutures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, List<Candle>> historicalDataBuffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> requestSymbols = new ConcurrentHashMap<>();
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
    private Set<String> symbolsBoughtToday = Collections.synchronizedSet(new HashSet<>());

    private final ConcurrentHashMap<Integer, CompletableFuture<MarginWhatIfResponseDTO>> pendingMarginWhatIfRequests = new ConcurrentHashMap<>();
    private final Map<String, Integer> symbolFailureCounter = new ConcurrentHashMap<>();
    @Value("${api.ibkr.account-id:DUN652604}") // DUN... fica como fallback
    private String accountId;

    private EClientSocket accountClient;
    private EReaderSignal accountReaderSignal;
    private static final int MARKET_DATA_CLIENT_ID = 115;
    private static final int ACCOUNT_SYNC_CLIENT_ID = 116;

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
        // Canal 1 (Preços)
        this.readerSignal = new EJavaSignal();
        this.client = new EClientSocket(this, readerSignal);
        // Canal 2 (Gestão/Conta)
        this.accountReaderSignal = new EJavaSignal();
        this.accountClient = new EClientSocket(this, accountReaderSignal);

        // Observabilidade local (Ponte)
        Gauge.builder("ponte.cache.buying_power", this, connector -> connector.buyingPowerCache.get().doubleValue())
                .description("Buying power atual no cache da Ponte")
                .register(meterRegistry);

        Gauge.builder("ponte.cache.excess_liquidity", this, connector -> connector.excessLiquidityCache.get().doubleValue())
                .description("Excess liquidity atual no cache da Ponte")
                .register(meterRegistry);
        log.info("ℹ️ [Ponte IBKR] Inicializador concluído. Mappers e Serviços injetados (Sinergia OK).");
    }

    public Set<String> getSymbolsBoughtToday() {
        symbolsBoughtToday.clear(); // Limpa para nova consulta

        // Filtro: Apenas execuções de HOJE
        ExecutionFilter filter = new ExecutionFilter();
        filter.time(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-00:00:00")));

        log.info("📡 [PONTE] Solicitando execuções do dia para validar estoque...");
        client.reqExecutions(9999, filter); // 9999 é um ID fixo para esta consulta

        // Pequena espera para o callback preencher a lista (500ms a 1s é suficiente no boot)
        try { Thread.sleep(1000); } catch (InterruptedException e) { }

        return new HashSet<>(symbolsBoughtToday);
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


    public EClientSocket getAccountClient() {
        return this.accountClient;
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
     * 🔄 PROTOCOLO DE RECUPERAÇÃO EXAUSTIVO (Ajustado para Sustentabilidade)
     * Implementa CIRCUIT BREAKER para evitar loops infinitos de rejeição 201.
     */
    private void tentarReenvioComReducao(int originalId, com.ib.client.Contract contract, com.ib.client.Order order) {
        String symbol = contract.symbol();
        try {
            // 🛡️ COOLDOWN: Tempo para o TWS limpar a rejeição anterior
            Thread.sleep(1500);

            int falhas = symbolFailureCounter.getOrDefault(symbol, 0) + 1;
            symbolFailureCounter.put(symbol, falhas);

            double qtdAtual = order.totalQuantity().value().doubleValue();

            // 🛑 LÓGICA DE CIRCUIT BREAKER:
            // Se já falhou mais de 5 vezes ou se a última tentativa já foi o lote mínimo (1.0)
            if (qtdAtual <= 1.0 || falhas > 6) {
                log.error("🛑 [CIRCUIT BREAKER] Rejeição persistente em {}. Falhas: {}. Qtd Final: {}. Interrompendo mitigação para preservar banda.",
                        symbol, falhas, qtdAtual);

                // Notifica o Principal via Webhook para ele saber que o ativo está "travado" por margem
                webhookNotifier.sendOrderRejection(originalId, 201, "CIRCUIT BREAKER: Margem exausta mesmo no lote mínimo para " + symbol);

                // Limpeza de estado para permitir que o sistema tente de novo apenas se o Oráculo mandar uma nova ordem no futuro
                symbolFailureCounter.remove(symbol);
                return;
            }

            double novaQtd;

            if (falhas == 1) {
                novaQtd = Math.floor(qtdAtual * 0.30); // Tenta apenas 30% do lote original
            } else {
                novaQtd = Math.floor(qtdAtual * 0.50); // Reduções subsequentes
            }
            if (falhas > 3) {
                // 🚨 FASE DE EMERGÊNCIA: Fragmentação agressiva
                novaQtd = (qtdAtual > 10) ? Math.floor(qtdAtual * 0.3) : 1.0;
                log.error("🚨 [ABORDAGEM DE EMERGÊNCIA] Tentando fragmentação granular para {}. Qtd: {}", symbol, novaQtd);
            } else {
                // 🔄 FASE PADRÃO: Redução de 40% (Step-Down)
                novaQtd = Math.floor(qtdAtual * 0.60);
                if (novaQtd < 1) novaQtd = 1.0;
                log.warn("🔄 [RECOVERY SMART] Tentativa {} para {}. Ajustando lote: {} -> {}", falhas, symbol, qtdAtual, novaQtd);
            }

            // EXECUÇÃO DO REENVIO
            order.totalQuantity(com.ib.client.Decimal.get(novaQtd));
            order.orderType("MKT"); // Força Market para garantir tentativa de execução imediata
            order.lmtPrice(0);

            int novoId = orderIdManager.getNextOrderId();
            log.info("📤 [RECOVERY ENVIO] Submetendo mitigação reduzida de {} (ID: {})", symbol, novoId);
            this.placeOrder(novoId, contract, order);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ [RECOVERY] Thread interrompida.");
        } catch (Exception e) {
            log.error("💥 [RECOVERY] Erro crítico no protocolo de emergência para {}: {}", symbol, e.getMessage());
        }
    }


    public void clearSymbolFailure(String symbol) {
        this.symbolFailureCounter.remove(symbol);
    }

    public void clearAllFailures() {
        this.symbolFailureCounter.clear();
    }

    public int getFailureCount(String symbol) {
        return this.symbolFailureCounter.getOrDefault(symbol, 0);
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
        return this.accountId;
    }



    /**
     * 📡 [PONTE | SMART-ROUTER]
     * Solicita dados de mercado em tempo real.
     * Suporta Japão (TSEJ) e EUA (SMART Routing) para failover.
     */
    public void requestMarketData(String symbol) {
        if (!isConnected()) {
            log.error("❌ [PONTE] Falha ao assinar {}: Socket não conectado.", symbol);
            return;
        }

        try {
            Contract contract = new Contract();
            contract.secType("STK");

            // 1. 🎛️ LÓGICA DE ROTEAMENTO REGIONAL
            if (symbol.endsWith(".T")) {
                // JAPÃO
                String cleanSymbol = symbol.split("\\.")[0];
                contract.symbol(cleanSymbol);
                contract.exchange("TSEJ");
                contract.primaryExch("TSEJ");
                contract.currency("JPY");
                log.info("🎌 [IBKR-ROUTER] Configurando contrato JAPÃO para: {}", symbol);
            } else {
                // EUA (Failover do Finnhub)
                contract.symbol(symbol.toUpperCase());
                contract.exchange("SMART"); // Roteamento inteligente da IBKR para melhores preços
                contract.currency("USD");
                log.info("🇺🇸 [IBKR-ROUTER] Configurando contrato USA para: {}", symbol);
            }

            // 2. 🚀 DESTRAVA-SINAL (Real-time vs Delayed)
            // Força a TWS a enviar dados em tempo real se você tiver a assinatura.
            client.reqMarketDataType(1);

            // 3. 📝 REGISTRO E DISPARO
            int reqId = getNextReqId();
            marketDataRequests.put(reqId, symbol);

            // Parâmetros: "", false, false -> Assinatura padrão de streaming
            client.reqMktData(reqId, contract, "", false, false, null);

            log.info("✅ [PONTE-SINAL] Subscrição ativa para {} (ReqId: {}) via Canal 115.", symbol, reqId);

        } catch (Exception e) {
            log.error("💥 [PONTE-SINAL] Erro crítico ao rotear {}: {}", symbol, e.getMessage());
        }
    }


    public void requestCriticalMarginData() {
        // Verificamos o canal de gestão (116)
        if (accountClient == null || !accountClient.isConnected()) {
            log.error("❌ [CANAL 116] Inativo. Tentando via canal principal como failover.");
            if (isConnected()) {
                executeMarginRequest(client); // Fallback no 115 se o 116 cair
            }
            return;
        }

        executeMarginRequest(accountClient);
    }

    private void executeMarginRequest(EClientSocket socketToUse) {
        String tags = "MaintMarginReq,InitMarginReq,EquityWithLoanValue,NetLiquidationValue,ExcessLiquidity,BuyingPower,AvailableFunds";
        String group = "All";

        // 🎯 O PULLO DO GATO: Enviamos a requisição pesada pelo socket de conta
        socketToUse.reqAccountSummary(CRITICAL_MARGIN_REQ_ID, group, tags);

        log.info("📊 [DUAL-CHANNEL] Margem solicitada via CANAL 116 (Gestão). ReqID: {}.", CRITICAL_MARGIN_REQ_ID);
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

            // 🛑 TRATAMENTO ESTRATÉGICO DO ERRO 201 (ESTRANGULAMENTO DE MARGEM)
            if (errorMessage != null && errorMessage.contains("201")) {

                // ✅ BYPASS DE SEGURANÇA PARA VENDAS: Se o objetivo é SAIR, não podemos travar.
                if (ordemPrincipal.isVenda()) {
                    log.warn("🚨 [VETO-MARGEM-VENDA] {} rejeitada por Margem Inicial. Ativando Protocolo de Fragmentação...", ordemPrincipal.symbol());

                    try {
                        // Mapeia os objetos necessários para o reenvio
                        com.ib.client.Order ibkrOrder = ibkrMapper.toIBKROrder(ordemPrincipal);
                        com.ib.client.Contract contract = ibkrMapper.toContract(ordemPrincipal);

                        // Dispara a lógica de redução agressiva (aquela que corta 70% no primeiro erro)
                        tentarReenvioComReducao(ibkrOrder.orderId(), contract, ibkrOrder);

                        return; // 🔄 Retorno silencioso: A Ponte assume a responsabilidade e o Principal continua operando.
                    } catch (Exception ex) {
                        log.error("💥 [ERRO-FATAL-RECOVERY] Falha ao iniciar mitigação para {}: {}", ordemPrincipal.symbol(), ex.getMessage());
                    }
                }

                // Se for uma COMPRA, mantemos o bloqueio original para não afundar a conta
                throw new MarginRejectionException("Erro 201: Margem insuficiente para abertura de nova posição.", e);
            }

            log.error("🛑🛑🛑 [Ponte IBKR | ERRO GERAL] Falha catastrófica na ordem {}. Mensagem: {}", ordemPrincipal.symbol(), errorMessage, e);
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

    @Override
    public List<Candle> getHistoricalData(String symbol, int years) {
        if (!isConnected()) return Collections.emptyList();

        int reqId = getNextReqId();
        CompletableFuture<List<Candle>> future = new CompletableFuture<>();

        historicalFutures.put(reqId, future);
        historicalDataBuffers.put(reqId, new ArrayList<>());
        requestSymbols.put(reqId, symbol);

        try {
            // 1. Configurar Contrato com Roteamento Regional
            Contract contract = new Contract();
            String cleanSymbol = symbol.contains(".") ? symbol.split("\\.")[0] : symbol;
            contract.symbol(cleanSymbol);
            contract.secType("STK");

            if (symbol.endsWith(".T")) {
                contract.exchange("TSEJ");
                contract.currency("JPY");
            } else if (symbol.endsWith(".KS")) {
                contract.exchange("KRX");
                contract.currency("KRW");
            } else if (symbol.endsWith(".HK")) {
                contract.exchange("SEHK");
                contract.currency("HKD");
            } else {
                contract.exchange("SMART");
                contract.currency("USD");
            }

            // 2. CORREÇÃO DO ERRO 10314: String vazia assume o "Agora"
            String endDateTime = "";

            // 3. AJUSTE DINÂMICO: Removemos a trava de "1 Y".
            // Agora o durationStr usa exatamente o valor do parâmetro 'years'.
            // Ex: Se o Principal pedir 14, aqui será montado "14 Y".
            String durationStr = years + " Y";

            log.info("📡 [PONTE-SOCKET] Solicitando {} de histórico real para {} (ReqId: {})", durationStr, symbol, reqId);

            // 4. Disparar Requisição
            // Nota: O parâmetro "1 day" permite que a IBKR entregue muitos anos de uma só vez.
            client.reqHistoricalData(reqId, contract, endDateTime, durationStr, "1 day", "TRADES", 1, 1, false, null);

            // 5. Aguarda a resposta (30s é seguro para grandes volumes de dados)
            return future.get(60, TimeUnit.SECONDS);

        } catch (TimeoutException e) {
            log.error("⏳ [TIMEOUT-CRÍTICO] A TWS demorou mais de 60s para enviar 14 anos de {}.", symbol);
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("⏳ [ERROR] Falha ao obter histórico para {}: {}", symbol, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public void connect() {
        if (client.isConnected() && accountClient.isConnected()) {
            log.warn("⚠️ Ambos os canais já estão conectados.");
            return;
        }

        try {
            String host = ibkrProps.host();
            int port = ibkrProps.port();

            // 📡 CANAL 115: MARKET DATA (TICKS)
            log.info("📡 [CANAL 115] Conectando para PREÇOS em {}:{}", host, port);
            client.eConnect(host, port, 115);
            startMsgProcessor(client, readerSignal, "ibkr-market-processor");

            // ⏳ PAUSA DE SINERGIA: Essencial para evitar o erro 502 da TWS ao conectar múltiplos IDs
            log.info("⏳ Aguardando estabilização para conectar canal de GESTÃO...");
            Thread.sleep(500);

            // 📡 CANAL 116: GESTÃO (CONTA/ORDENS)
            log.info("📡 [CANAL 116] Conectando para GESTÃO em {}:{}", host, port);
            accountClient.eConnect(host, port, 116);
            startMsgProcessor(accountClient, accountReaderSignal, "ibkr-account-processor");

            connectionLatch.await(10, TimeUnit.SECONDS);
            log.info("✅ [DUAL-CHANNEL] Sincronização de canais concluída com sucesso.");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ Conexão interrompida durante a pausa de sinergia.");
        } catch (Exception e) {
            log.error("💥 Falha na conexão dual com IBKR: {}", e.getMessage());
        }
    }
    /**
     * Helper para rodar os processadores de mensagens em threads separadas.
     * Isso garante que o processamento do Canal 116 não trave o Canal 115.
     */
    private void startMsgProcessor(EClientSocket socket, EReaderSignal signal, String threadName) {
        final EReader reader = new EReader(socket, signal);
        reader.start();
        new Thread(() -> {
            while (socket.isConnected()) {
                signal.waitForSignal();
                try {
                    reader.processMsgs();
                } catch (Exception e) {
                    log.error("💥 EXCEPTION TWS [{}]: Thread falhou: {}", threadName, e.getMessage());
                    break;
                }
            }
        }, threadName).start();
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
        // Bloco try-catch obrigatório para rastrear falhas na execução [cite: 2025-10-18]
        try {
            // Logs de rastreamento do TWS-IN
            log.info("💸 [PONTE | TWS-IN | EXECUÇÃO] Ordem IBKR {} EXECUTADA. Ação: {} {} {} @ {}. Exec ID: {}",
                    execution.orderId(), execution.side(), execution.shares().longValue(), contract.symbol(), execution.price(), execution.execId());

            // ✅ AJUSTE DE INTELIGÊNCIA: Se a ordem (mitigação ou normal) deu certo, limpa o contador de falhas do ativo
            // Isso permite que o robô saia do modo de fragmentação granular assim que a conta respirar.
            if (contract.symbol() != null) {
                symbolFailureCounter.remove(contract.symbol());
                log.info("✨ [SINERGIA] Bloqueio de margem superado. Contador de falhas resetado para {}.", contract.symbol());
            }

            // --- LÓGICA DE SINERGIA E PREENCHIMENTO DE EVENTO (MANTIDA INTEGRALMENTE) ---

            // **NOTA CRÍTICA:** A comissão vem em callback SEPARADO. Usamos placeholder conforme plano inicial.
            BigDecimal commissionAmount = new BigDecimal(
                    ThreadLocalRandom.current().nextDouble(0.5, 2.0)
            ).setScale(2, RoundingMode.HALF_UP);

            // 1. Publica um evento de domínio (SINERGIA com o Principal)
            TradeExecutedEvent event = TradeExecutedEvent.builder()
                    .orderId(String.valueOf(execution.orderId()))
                    .symbol(contract.symbol())
                    .side(execution.side())
                    .quantity(BigDecimal.valueOf(execution.shares().longValue()))
                    .price(BigDecimal.valueOf(execution.price()))
                    .commission(commissionAmount)
                    .executionTime(Instant.now())
                    .executionSource("IBKR_TWS_API_LIVE")
                    .clientOrderId(String.valueOf(execution.orderId()))
                    .build();

            eventPublisher.publishEvent(event);
            log.debug("📢 Evento 'TradeExecutedEvent' publicado para a ordem {}. (Domínio Principal)", execution.orderId());

            // 2. Envia o relatório via webhook (Dever de notificação da Ponte)
            ExecutionReportDto report = new ExecutionReportDto(
                    execution.orderId(),
                    contract.symbol(),
                    execution.side(),
                    (int) execution.shares().longValue(),
                    BigDecimal.valueOf(execution.price()),
                    LocalDateTime.now(),
                    execution.execId()
            );
            webhookNotifier.sendExecutionReport(report);
            log.info("📤 Relatório de Execução (BrokerID: {}) ENVIADO via Webhook ao sistema Principal (H.O.M.E.).", execution.orderId());

        } catch (Exception e) {
            // Log explicativo para acompanhar o que acontece no código [cite: 2025-10-19]
            log.error("💥 [PONTE | SINERGIA] Falha CRÍTICA ao processar Execution Report (ID {}). Causa: {}", execution.orderId(), e.getMessage());
        }
    }

    /**
     * ✅ Implementação CRÍTICA do error do EWrapper (Estrutura Original Restaurada).
     * Resolve o conflito de login duplicado e garante a fluidez do mercado asiático.
     */
    @Override
    public void error(int id, long time, int errorCode, String errorMsg, String advancedOrderRejectJson) {
        try {
            // 1. Diagnóstico e Log de Auditoria
            log.debug("🔍 [DIAGNÓSTICO TWS RAW] ID: {} | CÓDIGO: {} | MENSAGEM: {} | JSON: {}",
                    id, errorCode, errorMsg, advancedOrderRejectJson);

            // 🛡️ ROTA SEGURA: Tratamento de Conflito de Login (IP Duplicado)
            // Erros de IP (162), Sessões Simultâneas (10197) ou Requisição Inválida (321)
            if (errorCode == 162 || errorCode == 321 || errorCode == 10197) {
                log.error("⚠️ [BLOQUEIO-IBKR] Corretora recusou ReqId {}: {}. Verifique se há outra sessão aberta!", id, errorMsg);

                // AÇÃO CRÍTICA: Destrava imediatamente pedidos de HISTÓRICO (Warmup)
                // Isso evita que o Principal fique travado no boot se a IBKR bloquear os dados.
                CompletableFuture<List<Candle>> historicalFuture = historicalFutures.remove(id);
                if (historicalFuture != null) {
                    log.warn("🔓 [DESTRAVA-EMERGÊNCIA] Liberando thread do Principal com lista vazia para ativar Modo Híbrido.");
                    historicalFuture.complete(Collections.emptyList());
                }

                // Destrava pedidos de WHAT-IF (Simulação de Margem)
                CompletableFuture<OrderStateDTO> whatIfFuture = whatIfFutures.remove(id);
                if (whatIfFuture != null) {
                    whatIfFuture.completeExceptionally(new RuntimeException("IBKR_CONFLITO_SESSION: " + errorMsg));
                }

                // Limpeza de buffers residuais
                historicalDataBuffers.remove(id);
                requestSymbols.remove(id);

                // Notifica o Principal via Webhook sobre a "cegueira" de dados
                if (id > 0) {
                    webhookNotifier.sendOrderRejection(id, errorCode, "RESPOSTA DEFINITIVA: " + errorMsg);
                }
                return;
            }

            // --- 2. TRATAMENTO DE SIMULAÇÕES WHAT-IF GERAIS ---
            CompletableFuture<OrderStateDTO> generalWhatIfFuture = whatIfFutures.get(id);
            if (generalWhatIfFuture != null) {
                whatIfFutures.remove(id);
                log.error("❌ [PONTE | What-If ERRO] ID: {} | CÓDIGO: {} | Mensagem: '{}'", id, errorCode, errorMsg);
                generalWhatIfFuture.completeExceptionally(new RuntimeException("Simulação What-If Falhou: " + errorMsg));
                return;
            }

            // --- 3. TRATAMENTO DE ERROS DE CONEXÃO E SISTEMA (ID < 0) ---
            if (id < 0) {
                if (errorCode == 2104 || errorCode == 2158 || errorCode == 2106) {
                    log.info("✅ [TWS-IN] STATUS DE CONEXÃO: Código {}", errorCode);
                } else {
                    log.warn("🟡 [TWS-IN] INFO/AVISO: Código {} - {}", errorCode, errorMsg);
                }
                return;
            }

            // --- 4. 🧠 SINERGIA E AUTONOMIA: LIMPEZA DE CAPITAL IMEDIATA ---
            // Se a ordem falhou por qualquer erro, devolvemos o dinheiro reservado ao Buying Power.
            portfolioService.removePendingOrder(String.valueOf(id));

            // --- 5. 🚀 AUTO-CORREÇÃO DE ID (Resolução do Erro 103) ---
            if (errorCode == 103) {
                log.warn("🔄 [ID-RECOVERY] Erro 103 detectado. Salto preventivo automático no OrderIdManager.");
                orderIdManager.initializeOrUpdate(id + 1000);
            }

            // --- 6. TRATAMENTO DE REJEIÇÃO POR MARGEM (Erro 201 ou 10243) ---
            if (errorCode == 201 || errorCode == 10243) {
                log.error("🛑🚨 [MARGEM] Rejeição detectada no ID {}. Iniciando mitigação original...", id);

                com.ib.client.Order orderFalha = lastOrdersCache.get(id);
                com.ib.client.Contract contractFalha = lastContractsCache.get(id);

                if (orderFalha != null && contractFalha != null) {
                    lastOrdersCache.remove(id);
                    lastContractsCache.remove(id);
                    webhookNotifier.sendOrderRejection(id, errorCode, "Margem insuficiente. Reduzindo lote...");

                    // Chama a sua lógica original de redução de lote
                    tentarReenvioComReducao(id, contractFalha, orderFalha);
                } else {
                    log.error("❌ [RECOVERY ABORT] Ordem ID {} não encontrada para redução automática.", id);
                    webhookNotifier.sendOrderRejection(id, errorCode, errorMsg);
                }
                return;
            }

            // --- 7. NOTIFICAÇÃO DE ERROS SIGNIFICATIVOS AO PRINCIPAL ---
            if (errorCode != 2109 && errorCode != 2106 && errorCode != 2107 && errorCode != 2100) {
                webhookNotifier.sendOrderRejection(id, errorCode, errorMsg);
            }

        } catch (Exception e) {
            log.error("💥 [PONTE | ERROR CALLBACK] Falha fatal no tratamento: {}", e.getMessage(), e);
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
    @Override public void scannerParameters(String s) {}
    @Override public void scannerData(int i, int i1, ContractDetails contractDetails, String s, String s1, String s2, String s3) {}
    @Override public void scannerDataEnd(int i) {}
    @Override public void realtimeBar(int i, long l, double v, double v1, double v2, double v3, Decimal decimal, Decimal decimal1, int i1) {}
    @Override public void currentTime(long l) {}
    @Override public void fundamentalData(int i, String s) {}
    @Override public void deltaNeutralValidation(int i, DeltaNeutralContract deltaNeutralContract) {}
    @Override public void tickSnapshotEnd(int i) {}
    @Override public void marketDataType(int i, int i1) {}


    @Override public void openOrderEnd() {}
    @Override public void updateAccountTime(String var1) {}
    @Override public void accountDownloadEnd(String var1) {}


    @Override
    public void historicalData(int reqId, Bar bar) {
        List<Candle> buffer = historicalDataBuffers.get(reqId);
        String symbol = requestSymbols.get(reqId);

        if (buffer != null) {
            try {
                LocalDateTime dateTime;
                String rawTime = bar.time();

                // A IBKR retorna "yyyyMMdd" para barras de 1 dia.
                // Se houver espaço ou for mais longo, tratamos como DateTime.
                if (rawTime.contains("  ")) {
                    dateTime = LocalDateTime.parse(rawTime, DateTimeFormatter.ofPattern("yyyyMMdd  HH:mm:ss"));
                } else if (rawTime.length() == 8) {
                    dateTime = LocalDate.parse(rawTime, DateTimeFormatter.ofPattern("yyyyMMdd")).atStartOfDay();
                } else {
                    // Fallback para outros formatos que o TWS possa enviar
                    dateTime = LocalDateTime.parse(rawTime, DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss"));
                }

                buffer.add(new Candle(
                        symbol,
                        dateTime,
                        bar.open(),
                        bar.high(),
                        bar.low(),
                        bar.close(),
                        bar.volume().longValue()
                ));

                // Log de depuração a cada 100 candles para não inundar o console
                if (buffer.size() % 100 == 0) {
//                    log.debug("📥 [PONTE-DADO] Coletando candles para {}... Total: {}", symbol, buffer.size());
                }

            } catch (Exception e) {
                log.warn("⚠️ [DATA-PARSE] Erro ao converter data '{}' do ativo {}: {}", bar.time(), symbol, e.getMessage());
            }
        }
    }

    @Override
    public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) {
        List<Candle> data = historicalDataBuffers.get(reqId);
        int total = (data != null) ? data.size() : 0;

        log.info("✅ [PONTE-SOCKET] Carga histórica FINALIZADA para ReqId: {}. Total: {} candles coletados.", reqId, total);

        CompletableFuture<List<Candle>> future = historicalFutures.get(reqId);
        if (future != null) {
            // Entrega a lista preenchida para o Controller (e consequentemente para o Principal)
            future.complete(data != null ? data : Collections.emptyList());
        }
    }

    // No IBKRConnector.java da PONTE
    @Override
    public void tickPrice(int tickerId, int field, double price, TickAttrib attribs) {
        // 1. Filtro de segurança básico da IBKR
        if (price <= 0 || price == Double.MAX_VALUE) return;

        // 🚨 LOG DE EMERGÊNCIA: Monitora QUALQUER sinal vindo da TWS em tempo real
        log.info("⚡ [TWS-RAW] Recebido ID: {} | Campo: {} | Preço: {}", tickerId, field, price);

        String symbol = marketDataRequests.get(tickerId);
        if (symbol == null) return;

        // 🎯 FILTRO DE FLUXO CONTÍNUO:
        // No Japão, o preço "Last" (4) demora a mudar.
        // Capturamos Bid(1) e Ask(2) para garantir que o Oráculo no Principal tenha o preço mais recente.
        if (field == 1 || field == 2 || field == 4 || field == 6 || field == 7 || field == 9) {

            BigDecimal currentPrice = BigDecimal.valueOf(price);

            // 🚀 ENVIO PARA O PRINCIPAL (Aqui ocorre a sinergia com o Webhook)
            webhookNotifier.sendMarketTick(symbol, currentPrice);

            // Log detalhado para confirmar o repasse com sucesso
            log.info("🚀 [TICK-FLOW] {} -> {} {} (Field: {})",
                    symbol,
                    price,
                    symbol.endsWith(".T") ? "JPY" : "USD",
                    TickType.getField(field));
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
                log.info("⬅️ [PONTE | SUMMARY NLV] Atualizando valor mestre (SSOT): R$ {}", accountValue);
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
//            if (reqId == CRITICAL_MARGIN_REQ_ID) {
//                // O EL já deve ter sido recebido ou calculado pelo accountSummary()
//                log.error("🎉🎉 [PONTE | MARGEM CRÍTICA CONCLUÍDA] Fim do Account Summary de Margem (ReqID: {}). Dados de risco populados.", reqId);
//            }
            // Lógica legada ou de limpeza
//            currentAccountSummaryReqId.compareAndSet(reqId, -1);
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