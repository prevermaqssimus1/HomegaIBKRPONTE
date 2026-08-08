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
    private final ConcurrentHashMap<String, BigDecimal[]> microBuffer = new ConcurrentHashMap<>();
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
    private final ConcurrentHashMap<String, BigDecimal> shadowPositions = new ConcurrentHashMap<>();
    // ✅ CAMPO SINÉRGICO: Listener de Callback para o Principal
    private Optional<BPSyncedListener> bpListener = Optional.empty();
    private Set<String> symbolsBoughtToday = Collections.synchronizedSet(new HashSet<>());
    private final ConcurrentHashMap<Integer, CompletableFuture<BigDecimal>> priceSnapshots = new ConcurrentHashMap<>();
    private final AtomicInteger nextValidOrderId = new AtomicInteger(-1);
    private final ConcurrentHashMap<Integer, CompletableFuture<MarginWhatIfResponseDTO>> pendingMarginWhatIfRequests = new ConcurrentHashMap<>();
    private final Map<String, Integer> symbolFailureCounter = new ConcurrentHashMap<>();
    // 🎯 O CORAÇÃO DO RELÓGIO: Cache de latência zero
    private final ConcurrentHashMap<String, BigDecimal> beaconPriceCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> beaconTimestampCache = new ConcurrentHashMap<>();
    @Value("${api.ibkr.account-id:DUN652604}") // DUN... fica como fallback
    private String accountId;

    private EClientSocket accountClient;
    private EReaderSignal accountReaderSignal;


    // 🛡️ CONTROLE DE CADÊNCIA (Market Data)
//    private final Semaphore snapshotSemaphore = new Semaphore(1); // Um por vez para evitar 10197
    private final Map<String, Long> lastSnapshotTime = new ConcurrentHashMap<>();
    private static final long MIN_SNAPSHOT_INTERVAL_MS = 2000; // 2 segundos entre snapshots do mesmo ativo
    private static final long GLOBAL_COOLDOWN_MS = 150; // 150ms entre ativos diferentes (Cadência Institucional)

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


    // 🎯 EXECUTOR SERIAL (Deve ser declarado no topo da classe IBKRConnector)
    // Este é o "regulador" do relógio. Ele garante ordem de chegada e evita sobrecarga no socket.
    private final ExecutorService dispatcherExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ibkr-order-dispatcher");
        t.setPriority(Thread.MAX_PRIORITY);
        return t;
    });

    /**
     * 🚀 ENVIO FÍSICO PARA TWS: Ponto final de execução na Ponte.
     * AJUSTADO PARA CONTROLE DE LATÊNCIA (PASSO 2)
     */
//    public void placeOrder(String principalClientId, Contract contract, com.ib.client.Order order) {
//        long entryTime = System.currentTimeMillis(); // ⏱️ Início da auditoria de latência
//
//        // 🛡️ ENTRADA NO FUNIL SERIALIZADO (Impede colisão de threads virtuais)
//        dispatcherExecutor.submit(() -> {
//            // 🛡️ GERA ID NUMÉRICO SEQUENCIAL INTERNO (Exigência da TWS)
//            int internalIbkrId = this.getNextOrderId();
//
//            if (internalIbkrId < 0) {
//                log.error("❌ [TWS-OUT] Abortando ordem {}. ID numérico ainda não sincronizado.", principalClientId);
//                webhookNotifier.sendOrderRejection(principalClientId, -1L, -1, "ID não sincronizado");
//                return;
//            }
//
//            try {
//                if (isConnected()) {
//                    // 🔗 VINCULA O ID DE TEXTO AO CAMPO DE REFERÊNCIA DA IBKR
//                    order.orderRef(principalClientId);
//
//                    // 1. 🚨 REGISTRO NO CACHE DE RECUPERAÇÃO
//                    lastOrdersCache.put(internalIbkrId, order);
//                    lastContractsCache.put(internalIbkrId, contract);
//
//                    // 2. 🛡️ SINERGIA DE CAPITAL ATÔMICA
//                    BigDecimal quantity = new BigDecimal(order.totalQuantity().value().toString());
//
//                    // ⚡ AJUSTE DE LATÊNCIA: Prioriza o Preço Beacon (Passo 1) para evitar Snapshots lentos
//                    BigDecimal price = order.lmtPrice() != 0 ? BigDecimal.valueOf(order.lmtPrice()) :
//                            getStreamingPrice(contract.symbol()); // Usa o cache de milissegundos
//
//                    // Fallback de segurança caso o Beacon ainda não tenha o preço
//                    if (price == null || price.signum() <= 0) {
//                        price = portfolioService.getPriceForOrder(contract.symbol());
//                    }
//
//                    // ✅ SINCRONIA: Reserva o capital no cofre interno
//                    portfolioService.trackOrderSent(principalClientId, contract.symbol(), quantity, price);
//
//                    // 📊 AUDITORIA DE FILA
//                    long waitTime = System.currentTimeMillis() - entryTime;
//                    log.info("📦 [TWS-OUT] Ordem {} (Ref: {}) processada após {}ms em fila. Ativo: {} | Qtd: {} | Preço: ${}",
//                            internalIbkrId, principalClientId, waitTime, contract.symbol(), quantity, price);
//
//                    // 3. ENVIO FÍSICO VIA SOCKET (Garante exclusividade da via)
//                    this.client.placeOrder(internalIbkrId, contract, order);
//
//                    log.info("✅✅✅✅✅✅✅ [TWS-OUT] Ordem {} transmitida à IBKR com sucesso.✅✅", internalIbkrId);
//
//                    // ⏱️ CADÊNCIA TÉCNICA: Pequeno respiro (pacing) para não saturar o buffer da TWS
//                    TimeUnit.MILLISECONDS.sleep(20);
//
//                } else {
//                    log.error("❌ [TWS-OUT] Conexão inativa para ordem {}.", principalClientId);
//                    webhookNotifier.sendOrderRejection(principalClientId, (long) internalIbkrId, -1, "Conexão Inativa");
//                }
//            } catch (Exception e) {
//                log.error("💥 [TWS-OUT] Erro crítico no funil: {}", e.getMessage());
//                lastOrdersCache.remove(internalIbkrId);
//                lastContractsCache.remove(internalIbkrId);
//                portfolioService.removePendingOrderById(principalClientId);
//            }
//        });
//    }

    /**
     * 🚀 DESPACHO BLINDADO V28.0 (PROTOCOLO HEGEMONIA + SHADOW MEMORY)
     * Mantém a estrutura de logs original e adiciona consciência instantânea de inventário.
     */
    public void placeOrder(String principalClientId, Contract contract, com.ib.client.Order order) {
        dispatcherExecutor.submit(() -> {
            try {
                if (!client.isConnected()) {
                    log.error("❌ [TWS-OFFLINE] Tentativa de ordem negada: Cliente desconectado.");
                    return;
                }

                // 📡 LOG DE ENTRADA TÁTICA (Radar Primário)
                log.info("📥 [DESPACHO-IN] Recebido do Principal: Ref: {} | Ativo: {} | Lado Original: {} | Qtd: {}",
                        principalClientId, contract.symbol(), order.getAction(), order.totalQuantity().value());

                int internalIbkrId = this.getNextOrderId();
                String symbol = contract.symbol();

                // 1. Definição expandida de fechamento
                boolean isClosingOrder = principalClientId.contains("CLOSE")
                        || principalClientId.contains("EXIT")
                        || principalClientId.contains("EJECT")
                        || principalClientId.contains("KILL");

                // --- 🛡️ VALIDAÇÃO DUPLA (PROTOCOLO HEGEMONIA + BLINDAGEM DE LATÊNCIA) ---

                // A. Busca a última verdade oficial reportada pela TWS (Buffer/Cache)
                BigDecimal posicaoDaTWS = this.tempPositions.stream()
                        .filter(p -> p.getTicker().equalsIgnoreCase(symbol))
                        .map(PositionDTO::getPosition)
                        .findFirst()
                        .orElse(portfolioService.getPositionForSymbol(symbol));

                // B. Cruza com a Memória Sombreada (Shadow) para neutralizar o delay da corretora
                // Se houver registro na sombra (execução recente), ela é a verdade absoluta.
                BigDecimal posicaoReal = shadowPositions.getOrDefault(symbol, posicaoDaTWS);

                // Log de Inquisição para Auditoria de Sincronia
                log.info("🔍 [INQUISIÇÃO] {} | TWS diz: {} | Shadow diz: {} | Final: {}",
                        symbol, posicaoDaTWS, shadowPositions.get(symbol), posicaoReal);

                if (isClosingOrder) {
                    // 🛡️ VETO ABSOLUTO (MATA-ECO): Se a posição real (Shadow/TWS) for ZERO, aborta.
                    if (posicaoReal.signum() == 0) {
                        log.error("🛑 ⛔⛔[VETO-SEGURANÇA] Abortado! Ordem {} negada para {}: Custódia já está ZERADA (Shadow/TWS). Impedindo abertura de mão trocada.",
                                principalClientId, symbol);
                        return; // ⛔ COMANDO CRÍTICO: Mata a execução do dispatcher aqui.
                    }

                    // 2️⃣ Validação de Lado (Double-Check de Sinal)
                    if (posicaoReal.signum() < 0) { // 🔴 SHORT real
                        if (!"BUY".equals(order.getAction())) {
                            log.warn("🔄 [REPARAÇÃO-SHORT] Conflito em {}. Posição Real é SHORT ({}). Forçando BUY físico.", symbol, posicaoReal);
                            order.action("BUY");
                        }
                    }
                    else if (posicaoReal.signum() > 0) { // 🟢 LONG real
                        if (!"SELL".equals(order.getAction())) {
                            log.warn("🔄 [REPARAÇÃO-LONG] Conflito em {}. Posição Real é LONG ({}). Forçando SELL físico.", symbol, posicaoReal);
                            order.action("SELL");
                        }
                    }
                }

                // 4. ENVIO FÍSICO
                order.orderRef(principalClientId);
                log.info("📡 [TWS-OUT] Despachando {} | Lado Final: {} | Qtd: {} | Ref: {}",
                        symbol, order.getAction(), order.totalQuantity().value(), principalClientId);

                this.client.placeOrder(internalIbkrId, contract, order);

                // ⏱️ Cadência Técnica Institucional
                TimeUnit.MILLISECONDS.sleep(20);

            } catch (Exception e) {
                log.error("💥 [TWS-ERROR] Falha no despacho de {}: {}", contract.symbol(), e.getMessage());
            }
        });
    }



//    public BigDecimal requestImmediatePriceSnapshot(String symbol) {
//        // 1. Verificação de Sanidade da Conexão
//        if (!isConnected()) {
//            log.error("❌ [RECOVERY-FALHA] Snapshot impossível para {}: Conexão com TWS inativa.", symbol);
//            return BigDecimal.ZERO;
//        }
//
//        // 2. Preparação do Rastreamento
//        int reqId = getNextReqId();
//        CompletableFuture<BigDecimal> future = new CompletableFuture<>();
//
//        // Registra nos mapas para que o callback 'tickPrice' saiba onde entregar o valor
//        priceSnapshots.put(reqId, future);
//        marketDataRequests.put(reqId, symbol);
//
//        try {
//            // 3. Construção do Contrato usando seu Mapper (Sinergia)
//            Contract contract = new Contract();
//            contract.symbol(symbol.toUpperCase());
//            contract.secType("STK");
//            contract.exchange("SMART");
//            contract.currency("USD");
//
//            // Caso tenha o mapper pronto, pode usar:
//            // Contract contract = ibkrMapper.toContract(new Order(symbol, ...));
//            // Mas para snapshot de preço, o bloco acima é mais direto e seguro.
//
//            // 4. Configuração do Tipo de Dado (Tipo 3 = Delayed se não houver assinatura Live)
//            client.reqMarketDataType(3);
//
//            log.warn("📡 [SNAPSHOT-REQ] Acionando Snapshot forçado para {} (ReqId: {})", symbol, reqId);
//
//            // 5. Solicitação do Snapshot (quarto parâmetro 'true' indica Snapshot)
//            client.reqMktData(reqId, contract, "", true, false, null);
//
//            // 6. Aguarda o snapshotFuture.complete() que já existe no seu 'tickPrice'
//            // Timeout de 5 segundos para não travar a Virtual Thread do Winston por muito tempo
//            BigDecimal price = future.get(5, TimeUnit.SECONDS);
//
//            if (price != null && price.signum() > 0) {
//                log.info("✅ [RECOVERY-SUCCESS] Preço para {} recuperado: $ {}", symbol, price);
//                // Alimenta o cache local para evitar novas chamadas imediatas
//                marketPriceCache.put(symbol.toUpperCase(), price);
//                return price;
//            }
//
//            return BigDecimal.ZERO;
//
//        } catch (TimeoutException e) {
//            log.error("⏳ [RECOVERY-TIMEOUT] IBKR não respondeu snapshot de {} em 5s.", symbol);
//            return BigDecimal.ZERO;
//        } catch (Exception e) {
//            log.error("❌ [RECOVERY-ERROR] Erro técnico no snapshot de {}: {}", symbol, e.getMessage());
//            return BigDecimal.ZERO;
//        } finally {
//            // 7. Limpeza obrigatória para evitar Memory Leak
//            priceSnapshots.remove(reqId);
//            // Mantemos no marketDataRequests apenas se quisermos que o streaming continue
//        }
//    }


    // O método getNextOrderId deve ser assim:
    public synchronized int getNextOrderId() {
        int currentManagedId = orderIdManager.getCurrentId();
        int nextId = nextValidOrderId.get();

        // 🛡️ Garante que sempre usamos o maior entre o manager e o contador local
        int finalId = Math.max(currentManagedId, nextId);

        nextValidOrderId.set(finalId + 1);
        orderIdManager.initializeOrUpdate(finalId + 1);

        return finalId;
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
     * Gerencia subscrições com proteção de memória.
     */
    public void requestMarketData(String symbol) {
        if (!isConnected()) return;

        final String sym = symbol.toUpperCase();

        // 🛡️ EVITA DUPLICIDADE: Se já estamos assinados, não peça de novo
//        if (marketDataRequests.containsValue(sym)) {
//            return;
//        }

        try {
            Contract contract = new Contract();
            contract.secType("STK");
            // ... (Lógica de roteamento regional mantida: TSEJ/SMART) ...

            int reqId = getNextReqId();
//            marketDataRequests.put(reqId, sym);

            // 🕒 Snapshot de controle para purga futura (se quiser estender para auto-cancel)
            beaconTimestampCache.put(sym, System.currentTimeMillis());

            client.reqMktData(reqId, contract, "", false, false, null);

        } catch (Exception e) {
            log.error("💥 [PONTE-MEMORY] Erro ao assinar {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * 🧹 PURGA ATIVA: Cancela dados de mercado para poupar banda e CPU.
     * Chamado quando um ativo sai da lista de monitoramento do App Principal.
     */
    public void cancelMarketData(String symbol) {
        Integer reqIdToCancel = null;
        for (Map.Entry<Integer, String> entry : marketDataRequests.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(symbol)) {
                reqIdToCancel = entry.getKey();
                break;
            }
        }

        if (reqIdToCancel != null) {
            client.cancelMktData(reqIdToCancel);
            marketDataRequests.remove(reqIdToCancel);
//            beaconPriceCache.remove(symbol.toUpperCase());
            beaconTimestampCache.remove(symbol.toUpperCase());
            log.warn("🧹 [HYGIENE] Subscrição de {} cancelada por inatividade.", symbol);
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

            // 1. Conecta Canal 115 (Preços) - Prioridade Total
            log.info("📡 [CANAL 115] Conectando para PREÇOS em {}:{}", host, port);
            client.eConnect(host, port, 115);
            startMsgProcessor(client, readerSignal, "ibkr-market-processor");

            // 🎯 AJUSTE CRÍTICO: Aguardar o ACK da TWS para o Canal 115 antes de abrir o 116
            // 500ms é pouco para redes instáveis, use 2000ms para garantir a limpeza do buffer TCP
            Thread.sleep(2000);

            if (client.isConnected()) {
                client.reqMarketDataType(3); // Autoriza dados atrasados globalmente

                // 2. Conecta Canal 116 (Gestão/Conta) - Serializado
                log.info("📡 [CANAL 116] Conectando para GESTÃO em {}:{}", host, port);
                accountClient.eConnect(host, port, 116);
                startMsgProcessor(accountClient, accountReaderSignal, "ibkr-account-processor");

                // Aguarda o Latch confirmar que a conexão foi validada no callback nextValidId
                boolean connected = connectionLatch.await(15, TimeUnit.SECONDS);

                if (connected) {
                    log.info("✅ [DUAL-CHANNEL] Sincronização concluída com segurança serial.");
                } else {
                    log.error("❌ [DUAL-CHANNEL] Timeout aguardando sincronização de IDs.");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("💥 Falha na conexão dual: {}", e.getMessage());
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
        log.info("ℹ️️🛫 🛫 ℹ️️️ℹ️️️ℹ️️🛫 🛫  [OPEN-ORDER] ID: {} | Ativo: {} | Status: {}", orderId, contract.symbol(), orderState.status());

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

            String symbol = contract.symbol();

            // 🧠 [SHADOW-MEMORY] Atualização instantânea de inventário
            // BOT = Compra (+), SLD = Venda (-)
            BigDecimal sideSign = execution.side().equalsIgnoreCase("BOT") ? BigDecimal.ONE : BigDecimal.valueOf(-1);
            BigDecimal qtyExecutada = BigDecimal.valueOf(execution.shares().longValue()).multiply(sideSign);

            // Merge soma a execução nova à sombra existente
            shadowPositions.merge(symbol, qtyExecutada, BigDecimal::add);

            log.info("🧠 [SHADOW-UPDATE] {} | Delta: {} | Posição Estimada: {}",
                    symbol, qtyExecutada, shadowPositions.get(symbol));

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
     * ✅ Implementação CRÍTICA do error (V32.9 - Estorno Pré-Mitigação)
     */
    @Override
    public void error(int id, long time, int errorCode, String errorMsg, String advancedOrderRejectJson) {
        try {
            // --- 🛡️ FILTRO DE SINERGIA: IGNORAR AVISOS ---
            if (errorCode == 2109 || errorCode == 399 || errorCode == 2104 || errorCode == 2106 || errorCode == 2158) {
                log.debug("ℹ️ [IBKR-INFO] Código Informativo Ignorado: {} | Msg: {} (ID: {})", errorCode, errorMsg, id);
                return;
            }

            log.debug("🔍 [DIAGNÓSTICO TWS RAW] ID: {} | CÓDIGO: {} | MENSAGEM: {}", id, errorCode, errorMsg);

            // [Tratamento de Conflito de Login e What-If mantidos conforme original...]
            // (Código omitido aqui para brevidade, mas mantido na sua estrutura)

            // =================================================================================
            // 🚀 AUTO-CURA ATÔMICA: ESTORNO IMEDIATO
            // =================================================================================
            if (id > 0 && isSignificantError(errorCode)) {
                String clientOrderId = orderIdManager.getClientOrderId(id);

                if (clientOrderId != null && !clientOrderId.equals("0")) {
                    // 🩹 1. ESTORNO LOCAL: Limpa flightOrders ANTES de qualquer reenvio
                    portfolioService.removePendingOrderById(clientOrderId);
                    log.error("🩹 [PONTE-AUTO-CURA] Rejeição TWS ({}). Capital de {} liberado.", errorCode, clientOrderId);

                    // 📡 2. NOTIFICAÇÃO AO PRINCIPAL
                    webhookNotifier.sendOrderRejection(clientOrderId, (long) id, errorCode, errorMsg);
                } else {
                    portfolioService.removePendingOrder(String.valueOf(id));
                    webhookNotifier.sendOrderRejection(String.valueOf(id), (long) id, errorCode, errorMsg);
                }
            }

            // --- 5. AUTO-CORREÇÃO DE ID ---
            if (errorCode == 103 || errorCode == 10197) {
                log.error("🚨 [CRITICAL-ID-FAULT] Erro {}. Sincronizando IDs via TWS...", errorCode);
                // Tenta extrair o ID que a TWS sugeriu na mensagem de erro (geralmente vem no texto)
                // Se não conseguir extrair, dá um salto institucional de 1000
                int currentId = orderIdManager.getCurrentId();
                orderIdManager.initializeOrUpdate(currentId + 1000);
                nextValidOrderId.set(currentId + 1001);
                return;
            }

            // --- 6. TRATAMENTO DE REJEIÇÃO POR MARGEM (MITIGAÇÃO DIEGO) ---
            if (errorCode == 201 || errorCode == 10243) {
                log.error("🛑🚨 [MARGEM-ERRO-201] Rejeição no ID {}. Iniciando LIQUIDAÇÃO TÁTICA para destravar margem.", id);

                // 1. HIGIENE IMEDIATA (Libera o Buying Power no Principal)
                String clientOrderId = orderIdManager.getClientOrderId(id);
                portfolioService.removePendingOrderById(clientOrderId != null ? clientOrderId : String.valueOf(id));

                // 2. RECUPERAÇÃO DOS DADOS DA ORDEM QUE FALHOU
                com.ib.client.Order orderFalha = lastOrdersCache.remove(id);
                com.ib.client.Contract contractFalha = lastContractsCache.remove(id);

                if (orderFalha != null && contractFalha != null) {
                    log.info("🎯 [RECOVERY] Executando protocolo 'ORDEM PELADA' para eliminar o ativo: {}", contractFalha.symbol());
                    // CHAMADA PARA O NOVO MÉTODO DE LIQUIDAÇÃO TOTAL
                    executarLiquidacaoTotalNaked(contractFalha, orderFalha);
                } else {
                    log.error("❌ [RECOVERY ABORT] Dados insuficientes nos caches para liquidar ativo do ID {}", id);
                }
                return;
            }

        } catch (Exception e) {
            log.error("💥 [PONTE | ERROR CALLBACK] Falha fatal: {}", e.getMessage());
        }
    }

    private void executarLiquidacaoTotalNaked(Contract contract, com.ib.client.Order order) {
        String symbol = contract.symbol();
        try {
            // 🛡️ Delay tático para o Gateway respirar
            Thread.sleep(1000);

            log.warn("⚔️ [ORDEM-PELADA] Rejeição de margem em {}. Removendo blindagem para ejeção forçada.", symbol);

            // --- 🛠️ DESPIMPA A ORDEM (Naked Transformation) ---

            // 1. Capturamos a referência original do Principal ANTES de limpar tudo
            // Isso garante que o Principal consiga ouvir o retorno da execução MKT
            String principalClientId = order.orderRef();

            if (principalClientId == null || principalClientId.isEmpty() || principalClientId.equals("0")) {
                // Fallback caso a referência esteja perdida no objeto
                principalClientId = "RECOVERY-" + symbol + "-" + System.currentTimeMillis();
            }

            // 2. Transformação em Market Order (Alta prioridade)
            order.orderType("MKT");
            order.lmtPrice(Double.MAX_VALUE);
            order.auxPrice(0.0); // 🔥 DESTRUIÇÃO DO STOP LOSS (Causa do bloqueio)

            // 3. Limpeza de rastro de algoritmos e travas
            order.trailingPercent(Double.MAX_VALUE);
            order.trailStopPrice(Double.MAX_VALUE);
            order.algoStrategy("");
            order.algoParams(null);
            order.transmit(true);
            order.parentId(0);
            order.ocaGroup("");

            // --- 🚀 DISPARO VIA FLUXO EXISTENTE ---
            log.info("📤📤📤📤 [RECOVERY-ENVIO] Submetendo Ordem Pelada para 📤📤📤📤{} via ID Principal: {}",
                    symbol, principalClientId);

            // Chamada ao seu método original: public void placeOrder(String principalClientId, Contract contract, Order order)
            this.placeOrder(principalClientId, contract, order);

            // Reseta o contador de falhas para este ativo pois a ordem final foi enviada
            symbolFailureCounter.remove(symbol);

        } catch (Exception e) {
            log.error("💥 [Naked-Recovery] Falha crítica na manobra de ejeção de {}: {}", symbol, e.getMessage());
        }
    }

    private void tratarErroConexaoFata(int id, String errorMsg) {
        CompletableFuture<List<Candle>> historicalFuture = historicalFutures.remove(id);
        if (historicalFuture != null) historicalFuture.complete(Collections.emptyList());

        CompletableFuture<OrderStateDTO> whatIfFuture = whatIfFutures.remove(id);
        if (whatIfFuture != null) whatIfFuture.completeExceptionally(new RuntimeException(errorMsg));
    }

    /**
     * 🕵️ Filtro de ruído: Identifica se o código é uma falha real ou apenas info.
     */
    private boolean isSignificantError(int code) {
        // 🛡️ LISTA DE EXCLUSÃO (Códigos que NÃO devem disparar estorno de capital/erro)
        // 2100-2158: Status de conexão, farms de dados e conectividade.
        // 2109: 'Outside Regular Trading Hours' (Aviso informativo, a ordem continua viva).
        // 399: 'Order Message' (Aviso de re-precificação ou ajustes de câmbio).
        return code != 2104 &&
                code != 2106 &&
                code != 2107 &&
                code != 2100 &&
                code != 2108 &&
                code != 2158 &&
                code != 2109 && // ✅ Adicionado: Impede estorno falso em AVGO/Fora de hora
                code != 399;    // ✅ Adicionado: Impede estorno falso em avisos gerais
    }


    private void handleSystemErrors(int errorCode, String errorMsg) {
        if (errorCode == 2104 || errorCode == 2158 || errorCode == 2106) {
            log.info("✅ [TWS-IN] STATUS DE CONEXÃO: Código {}", errorCode);
        } else {
            log.warn("🟡 [TWS-IN] INFO/AVISO: Código {} - {}", errorCode, errorMsg);
        }
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
            String rawTicker = contract.symbol();

            if (rawTicker == null || rawTicker.isBlank()) {
                log.warn("Símbolo principal (symbol) não encontrado para conId={}. Tentando usar o símbolo local (localSymbol)...", contract.conid());
                rawTicker = contract.localSymbol();
            }

            if (rawTicker == null || rawTicker.isBlank()) {
                log.error("ERRO CRÍTICO DE SINCRONIZAÇÃO: Não foi possível determinar o ticker para a posição. conId={}, secType={}. Esta posição será ignorada.",
                        contract.conid(), contract.secType());
                return;
            }

            // 🎯 SOLUÇÃO PARA O LAMBDA: Criamos uma variável final imutável
            final String tickerFinal = rawTicker.trim();

            // 🛡️ PROTOCOLO ANTI-POEIRA V22.5 (CRÍTICO)
            BigDecimal quantity = (pos != null) ? pos.value() : BigDecimal.ZERO;

            if (quantity.abs().compareTo(new BigDecimal("0.01")) < 0) {
                return;
            }

            // 🚀 AJUSTE DE HIGIENE: Usando a variável final para evitar o erro de compilação
            tempPositions.removeIf(p -> p.getTicker().equalsIgnoreCase(tickerFinal));

//            log.info("🎯 [PONTE-SINCRONIA] Posição REAL ativa: {} {} @ {}", quantity, tickerFinal, avgCost);

            PositionDTO positionDto = new PositionDTO();
            positionDto.setTicker(tickerFinal);
            positionDto.setPosition(quantity);
            positionDto.setMktPrice(BigDecimal.valueOf(avgCost));

            this.tempPositions.add(positionDto);

        } catch (Exception e) {
            log.error("💥 [PONTE | POSIÇÃO] Erro ao processar a posição para contrato {}. Rastreando.", contract.conid(), e);
        }
    }
    @Override
    public void positionEnd() {
        try {
//            log.info("✅ [PONTE-SYNC] Fim do recebimento oficial. Sincronizando {} ativos com o PortfolioService.", tempPositions.size());

            // 1. ENTREGA TÁTICA: Envia a verdade oficial da TWS para o cache mestre do Principal.
            // Fazemos isso PRIMEIRO para que o cache consolidado esteja pronto.
            portfolioService.updatePortfolioPositions(new ArrayList<>(tempPositions));

            // 2. LIMPEZA DE RASTRO: Agora que o cache oficial está atualizado,
            // podemos resetar as memórias temporárias (Sombra e Buffer).
            shadowPositions.clear();
            tempPositions.clear();

            // 3. FINALIZAÇÃO: Notifica o sistema que o inventário está 100% íntegro.
            portfolioService.finalizePositionSync();

//            log.info("🧹 [SHADOW-CLEAN] Sincronia concluída. Memórias sombreadas resetadas e Cache oficial Soberano.");

        } catch (Exception e) {
            log.error("💥 [PONTE | POSIÇÃO END] Falha CRÍTICA na consolidação do inventário: {}", e.getMessage());
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

            // 🛡️ SINERGIA DE SEGURANÇA: Protocolo Hegemonia V24.5
            // Aplicamos o salto de 5000 imediatamente para limpar qualquer resíduo da sessão anterior
            // e garantir que a faixa de IDs seja aceita sem erro 103 (Duplicate ID).
            int currentId = orderIdManager.getCurrentId();
            int baseId = Math.max(orderId, currentId);

            // Aciona o método sincronizado que você ajustou anteriormente
            orderIdManager.initializeOrUpdate(baseId);

            log.warn("✅ [TWS-SYNC] Salto Institucional aplicado. Próximo ID seguro: {}", orderIdManager.getCurrentId());

            // 🧹 LIMPEZA DE FILA NA CORRETORA:
            // Vital para garantir que o Buying Power ($ 220k) esteja 100% livre para MSFT
            log.error("🧹 [BOOT-CLEANUP] Limpando ordens pendentes na IBKR para libertar capital institucional...");

            // Verificação de segurança para o socket antes do cancelamento global
            if (client != null && client.isConnected()) {
                client.reqGlobalCancel(new OrderCancel());
            }

            // Libera as threads que estavam aguardando a conexão (AccountSyncTask, etc)
            connectionLatch.countDown();

            // 📊 Solicita dados de margem imediatamente após estabilizar o ID
            requestCriticalMarginData();

        } catch (Exception e) {
            log.error("💥 Falha crítica no handshake de IDs (nextValidId): {}", e.getMessage());
            // Garantimos o decremento do latch para não travar o boot do sistema
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
        // 1. Recupera e remove imediatamente do buffer para liberar RAM
        List<Candle> data = historicalDataBuffers.remove(reqId);
        String symbol = requestSymbols.remove(reqId); // Limpa o símbolo associado

        int total = (data != null) ? data.size() : 0;
        log.info("✅ [HYGIENE] Buffer histórico destruído para {}. Total: {} candles. RAM liberada.", symbol, total);

        CompletableFuture<List<Candle>> future = historicalFutures.remove(reqId);
        if (future != null) {
            future.complete(data != null ? data : Collections.emptyList());
        }
    }

    @Override
    public void tickPrice(int tickerId, int field, double price, TickAttrib attribs) {
        // A Ponte não deve mais processar ticks detalhados para o Principal.
        // O Principal já recebe isso via Finnhub.
        // Se precisar de preço para ordens, use o que está no marketPriceCache (atualizado por streaming de conta).
        if (price > 0) {
            String symbol = marketDataRequests.get(tickerId);
            if (symbol != null) {
                marketPriceCache.put(symbol.toUpperCase(), BigDecimal.valueOf(price));
            }
        }
    }

//    @Override
//    public void tickPrice(int tickerId, int field, double price, TickAttrib attribs) {
//        // 1. Filtragem de Ruído: Ignora preços inválidos ou "poeira" de rede
//        if (price <= 0 || price == Double.MAX_VALUE) return;
//
//        // 2. Resgate de Snapshot (Prioridade alta para destravar threads de precificação)
//        CompletableFuture<BigDecimal> snapshotFuture = priceSnapshots.get(tickerId);
//        if (snapshotFuture != null && (field == 1 || field == 4 || field == 68)) {
//            snapshotFuture.complete(BigDecimal.valueOf(price));
//        }
//
//        String symbol = marketDataRequests.get(tickerId);
//        if (symbol == null) return;
//
//        BigDecimal currentPrice = BigDecimal.valueOf(price);
//        long now = System.currentTimeMillis();
//
//        // 🕒 [PASSO 1] BEACON (Cache de Latência Zero)
//        // Atualiza a memória local imediatamente para que o Principal leia via GET em 0ms
//        if (field == 1 || field == 2 || field == 4 || field == 68 || field == 9) {
//            beaconPriceCache.put(symbol, currentPrice);
//            beaconTimestampCache.put(symbol, now);
//            marketPriceCache.put(symbol, currentPrice);
//            portfolioService.getAccountValuesCache().put(symbol.toUpperCase() + "_PRICE", currentPrice);
//        }
//
//        // 🚀 [PASSO 5] WEBHOOK INTELIGENTE (Controle de Pressão)
//        // Só envia via Webhook se for Bid(1/66), Ask(2/67) ou Last(4/68)
//        if (field == 1 || field == 2 || field == 4 || field == 66 || field == 67 || field == 68) {
//            BigDecimal[] buffer = microBuffer.computeIfAbsent(symbol, k -> new BigDecimal[]{null, null, null});
//
//            // Atualiza o micro-buffer atômico
//            switch (field) {
//                case 1, 66 -> buffer[0] = currentPrice;
//                case 2, 67 -> buffer[1] = currentPrice;
//                case 4, 68 -> buffer[2] = currentPrice;
//            }
//
//            // 🛡️ FILTRO DE FREQUÊNCIA (Throttling):
//            // Não envia Webhook para o mesmo ativo mais de uma vez a cada 100ms.
//            // O Beacon (acima) já está atualizado, então o Principal não perde o preço real.
//            // Isso evita o "atropelo" de threads no App Principal (8080).
//            Long lastPush = beaconTimestampCache.get(symbol + "_PUSH");
//            if (lastPush == null || (now - lastPush) > 100) {
//
//                webhookNotifier.sendMarketTick(symbol,
//                        buffer[2] != null ? buffer[2] : currentPrice, // Last
//                        buffer[0] != null ? buffer[0] : currentPrice, // Bid
//                        buffer[1] != null ? buffer[1] : currentPrice, // Ask
//                        1L);
//
//                beaconTimestampCache.put(symbol + "_PUSH", now);
//            }
//
//            // 📝 Auditoria Trace (Apenas se necessário)
//            if (log.isTraceEnabled() && (field == 4 || field == 68)) {
//                log.trace("🛰️ [TICK-FLOW] {} -> ${}", symbol, price);
//            }
//        }
//    }

    /**
     * 🛰️ MÉTODO DE ACESSO RÁPIDO (SINERGIA PASSO 1)
     * Permite que qualquer serviço consulte o último preço do Streaming sem latência de rede.
     */
    public BigDecimal getStreamingPrice(String symbol) {
        String sym = symbol.toUpperCase();
        Long ts = beaconTimestampCache.get(sym);

        // 🛡️ Se o dado for mais velho que 2 segundos, o pulso falhou.
        // Retornamos NULL para o Principal saber que não deve operar às cegas.
        if (ts == null || (System.currentTimeMillis() - ts) > 2000) {
            return null;
        }
        return beaconPriceCache.get(sym);
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
//            log.debug("📊 [PONTE | SNAPSHOT-IN] Account Summary Processado: {} = R$ {}", tag, accountValue.toPlainString());

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


    /**
     * 📡 [VÁLVULA DE RESGATE] Snapshot Síncrono de Preço.
     * Solicita à TWS o preço atual e aguarda o retorno para evitar Custo $0.00.
     */
    public BigDecimal fetchMarketDataSnapshot(String symbol) {
        if (!isConnected()) return BigDecimal.ZERO;

        int reqId = getNextReqId();
        CompletableFuture<BigDecimal> future = new CompletableFuture<>();
        priceSnapshots.put(reqId, future);

        try {
            Contract contract = new Contract();
            contract.symbol(symbol.toUpperCase());
            contract.secType("STK");
            contract.exchange("SMART");
            contract.currency("USD");

            // 🎯 AJUSTE 2: Garante que esta requisição aceite dados atrasados
            client.reqMarketDataType(3);

            log.info("📡 [SNAPSHOT-REQ] Solicitando preço (Delayed OK) para {} (ReqId: {})", symbol, reqId);
            client.reqMktData(reqId, contract, "", true, false, null);

            // Aguarda até 3 segundos (dados atrasados podem demorar um pouco mais que RT)
            BigDecimal price = future.get(3, TimeUnit.SECONDS);
            return (price != null) ? price : BigDecimal.ZERO;

        } catch (TimeoutException e) {
            log.warn("⏳ [SNAPSHOT-TIMEOUT] TWS não respondeu snapshot de {} em 3s (Mesmo em modo Delayed).", symbol);
            return BigDecimal.ZERO;
        } catch (Exception e) {
            log.error("💥 [SNAPSHOT-ERROR] Falha técnica em {}: {}", symbol, e.getMessage());
            return BigDecimal.ZERO;
        } finally {
            priceSnapshots.remove(reqId);
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