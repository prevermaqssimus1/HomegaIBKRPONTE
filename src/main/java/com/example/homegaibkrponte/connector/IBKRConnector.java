package com.example.homegaibkrponte.connector;


import com.example.homegaibkrponte.connector.dto.AccountSummaryDTO;
import com.example.homegaibkrponte.data.MarketDataProvider;
import com.example.homegaibkrponte.dto.ExecutionReportDTO;
import com.example.homegaibkrponte.model.Candle;
import com.example.homegaibkrponte.model.PositionDTO;
import com.example.homegaibkrponte.model.TradeExecutedEvent;
import com.example.homegaibkrponte.monitoring.LivePortfolioService;
import com.example.homegaibkrponte.properties.IBKRProperties;
import com.example.homegaibkrponte.service.OrderIdManager;
import com.example.homegaibkrponte.service.WebhookNotifierService;
import com.ib.client.*;
import com.ib.client.protobuf.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ADAPTADOR CENTRAL (MarketDataProvider) e OBSERVER (EWrapper).
 * Implementa EWrapper diretamente para máxima compatibilidade com o TwsApi.jar.
 */
@Service
@Slf4j
public class IBKRConnector implements MarketDataProvider, EWrapper { // <<< IMPLEMENTAÇÃO DIRETA

    private final IBKRProperties ibkrProps;
    private final WebhookNotifierService webhookNotifier; // Você declarou como final (ótimo!)
    private final AtomicReference<BigDecimal> buyingPowerCache = new AtomicReference<>(BigDecimal.ZERO); // Cache de Saldo
    private final List<PositionDTO> tempPositions = new ArrayList<>();
    private final LivePortfolioService portfolioService; // <-- Adicionado
    private final ApplicationEventPublisher eventPublisher; // <-- Adicionado
    @Autowired
    private OrderIdManager orderIdManager;



    private EClientSocket client;
    private EReaderSignal readerSignal;
    private final AtomicInteger nextValidId = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, CompletableFuture<List<Candle>>> pendingHistoricalData = new ConcurrentHashMap<>();
    private final CountDownLatch connectionLatch = new CountDownLatch(1);


    @Autowired
    public IBKRConnector(IBKRProperties props,
                         WebhookNotifierService notifier,
                         LivePortfolioService portfolioService,
                         ApplicationEventPublisher eventPublisher,
                         OrderIdManager orderIdManager) { // <-- Adicionado aqui
        this.ibkrProps = props;
        this.webhookNotifier = notifier;
        this.portfolioService = portfolioService;
        this.eventPublisher = eventPublisher;
        this.orderIdManager = orderIdManager; // <-- Adicionado aqui

        this.readerSignal = new EJavaSignal();
        this.client = new EClientSocket(this, readerSignal);
    }

    // --- MÉTODOS AUXILIARES PÚBLICOS (Para o Controller REST) ---
    public int getNextReqId() { return nextValidId.getAndIncrement(); }
    public EClientSocket getClient() { return client; }
    public BigDecimal getBuyingPowerCache() { return buyingPowerCache.get(); }

    public String getAccountId() {
        // ⚠️ PONTO CRÍTICO: Idealmente, o ID da conta deve vir do seu arquivo de propriedades (ibkrProps).
        // Se a propriedade 'accountId' estiver nas suas IBKRProperties, use: return ibkrProps.accountId();

        // Use um valor temporário/fixo, mas **SUBSTITUA PELO SEU ACCOUNT ID REAL!**
        return "DUN652604";
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
                        // Tenta processar mensagens. Se houver falha de dependência, avança.
                        reader.processMsgs();
                    } catch (java.lang.NoClassDefFoundError ncdfe) {
                        // 🛑 TRATAMENTO CRÍTICO PARA O JAR HELL 🛑
                        // AQUI SABEMOS QUE O PROBLEMA É PROTOBUF. Logamos, mas EVITAMOS CRASHAR O LOOP.
                        log.error("🛑 ERRO FATAL DE CLASSPATH! Versão do Protobuf incompatível. MANTENDO CONEXÃO.", ncdfe);
                        // NÃO CHAMAMOS BREAK ou COUNTDOWN aqui para que a thread continue aguardando o próximo sinal.
                        // O ideal é que o EReader lide com isso, mas estamos nos protegendo da API.
                    } catch (Exception e) {
                        // Se for uma exceção de I/O ou conexão, aí sim saímos do loop.
                        log.error("💥 EXCEPTION TWS: Thread de processamento de mensagens falhou: {}", e.getMessage(), e);
                        break;
                    }
                }
            }, "ibkr-msg-processor").start();

            connectionLatch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("💥 Falha na conexão com IBKR: {}", e.getMessage(), e);
        }
    }

    @Override
    public void orderStatus(int orderId, String status, Decimal filled, Decimal remaining, double avgFillPrice, long permId, int parentId, double lastFillPrice, int clientId, String whyHeld, double mktCapPrice) {
        // CORREÇÃO: Removida a chamada redundante ao webhookNotifier.
        // Este método agora serve apenas para logar o status da ordem.
        // A fonte de verdade para execuções é o callback execDetails.
        log.warn("⬅️  [TWS-IN] STATUS Ordem {}: {} | Preenchido: {}/{} | Preço Médio: {}",
                orderId, status.toUpperCase(), filled, filled.add(remaining), avgFillPrice);
    }

    @Override public void openOrder(int orderId, Contract contract, Order order, OrderState orderState) {
        // CORREÇÃO: Usando os parâmetros corretos: orderId, order, contract, orderState
        log.info("ℹ️  [TWS-IN] OPEN Ordem {}: {} {} @ {} | Status: {}",
                orderId, order.action(), order.totalQuantity(), contract.symbol(), orderState.status());
    }

    @Override
    public void execDetails(int reqId, Contract contract, Execution execution) {
        log.info("💸 [TWS-IN] EXECUÇÃO Ordem {}: {} {} {} @ {} | Exec ID: {}",
                execution.orderId(), execution.side(), execution.shares(), contract.symbol(), execution.price(), execution.execId());

        // 1. Publica um evento de domínio para o sistema interno (LivePortfolioService)
        TradeExecutedEvent event = new TradeExecutedEvent(
                contract.symbol(),
                execution.side(),
                execution.shares().value(),
                BigDecimal.valueOf(execution.price()),
                LocalDateTime.now(),
                "LIVE",
                String.valueOf(execution.orderId())
        );
        eventPublisher.publishEvent(event);
        log.debug("📢 Evento 'TradeExecutedEvent' publicado para a ordem {}.", execution.orderId());

        // 2. Envia o relatório via webhook para o sistema H.O.M.E.
        ExecutionReportDTO report = new ExecutionReportDTO(
                execution.orderId(),
                contract.symbol(),
                execution.side(),
                execution.shares().value(),
                execution.price(),
                "EXEC"
        );
        webhookNotifier.sendExecutionReport(report);
    }



    public void error(int id, int errorCode, String errorMsg, Exception exception) {
        log.error("API Error: ID={}, Code={}, Msg={}", id, errorCode, errorMsg, exception);
        if (id > 0) {
            log.error("❌ [TWS-IN] ERRO na Ordem {}: Código {}, Mensagem: '{}'", id, errorCode, errorMsg);
        } else {
            log.error("❌ [TWS-IN] ERRO de Sistema: Código {}, Mensagem: '{}'", errorCode, errorMsg, exception);
        }
    }


    @Deprecated
    @Override
    public void updatePortfolio(Contract contract, Decimal position, double marketPrice, double marketValue,
                                double averageCost, double unrealizedPNL, double realizedPNL, String accountName) {
        // CORREÇÃO: Deixado vazio intencionalmente. A atualização de posições agora é feita
        // exclusivamente via position() e positionEnd() para evitar condição de corrida e estado inconsistente.
    }

    @Override
    public void position(String account, Contract contract, Decimal pos, double avgCost) {
        // 1. Tenta obter o símbolo principal.
        String ticker = contract.symbol();

        // 2. Lógica de Fallback: Se o símbolo principal for nulo ou vazio, tenta o símbolo local.
        if (ticker == null || ticker.isBlank()) {
            log.warn("Símbolo principal (symbol) não encontrado para conId={}. Tentando usar o símbolo local (localSymbol)...", contract.conid());
            ticker = contract.localSymbol();
        }

        // 3. Validação final: Se ainda assim for nulo, loga um erro e pula a posição.
        if (ticker == null || ticker.isBlank()) {
            log.error("ERRO CRÍTICO DE SINCRONIZAÇÃO: Não foi possível determinar o ticker para a posição. conId={}, secType={}. Esta posição será ignorada.",
                    contract.conid(), contract.secType());
            return; // Pula a adição desta posição inválida na lista.
        }

        // 4. Cria e adiciona o DTO apenas se o ticker for válido.
        log.debug("Posição recebida: {} {} @ {}", pos.value(), ticker, avgCost);
        PositionDTO positionDto = new PositionDTO();
        positionDto.setTicker(ticker.trim()); // .trim() para remover espaços em branco
        positionDto.setPosition(pos.value());
        positionDto.setMktPrice(BigDecimal.valueOf(avgCost));
        this.tempPositions.add(positionDto);
    }


    @Override
    public void positionEnd() {
        log.info("✅ Fim do recebimento de posições. Sincronizando {} posições com o PortfolioService.", tempPositions.size());
        // Envia a lista completa para o serviço de portfólio de uma só vez.
        portfolioService.updatePortfolioPositions(new ArrayList<>(tempPositions));
        tempPositions.clear(); // Limpa a lista para a próxima sincronização.
        portfolioService.finalizePositionSync();
    }

    // Lógica principal de sincronização de saldo
    // Em IBKRConnector.java

    // Em IBKRConnector.java

    // Em: IBKRConnector.java

    @Override
    public void updateAccountValue(String key, String value, String currency, String accountName) {
        try {
            // Logamos para observabilidade, mas só agimos em chaves específicas.
            log.trace("[TWS-IN] AccountValue: key={}, value={}, currency={}", key, value, currency);

            // Filtramos para agir APENAS na chave que representa o Poder de Compra.
            if ("BuyingPower".equalsIgnoreCase(key)) {
                BigDecimal buyingPower = new BigDecimal(value);

                // ✅ PONTO DE SINERGIA: Chama o método granular no LivePortfolioService.
                // Esta é a única linha que importa para a sincronização de saldo.
                portfolioService.updateAccountValue(key, buyingPower);
            }
        } catch (NumberFormatException e) {
            log.debug("Valor não numérico recebido para a tag '{}' no resumo da conta: {}", key, value);
        } catch (Exception e) {
            log.error("Erro inesperado ao processar updateAccountValue: key={}, value={}", key, value, e);
        }
    }

    @Override public void disconnect() { if (client.isConnected()) { client.eDisconnect(); log.warn("🔌 Desconectado do TWS/IB Gateway."); } }
    @Override public void subscribe(String symbol) { /* Vazio */ }
    @Override public boolean isConnected() { return client != null && client.isConnected(); }

    // ==========================================================
    // MÉTODOS EWrapper (IMPLEMENTAÇÃO COMPLETA)
    // ==========================================================

    @Override
    public void nextValidId(int orderId) {
        log.info("✅ Conexão estabelecida com sucesso. Próximo ID de Ordem Válido: {}", orderId);
        orderIdManager.initializeOrUpdate(orderId);
        connectionLatch.countDown(); // Libera a thread de conexão principal
    }

    @Override
    public void contractDetails(int i, ContractDetails contractDetails) {

    }

    @Override
    public void bondContractDetails(int i, ContractDetails contractDetails) {

    }

    @Override
    public void contractDetailsEnd(int i) {

    }



    // --- MÉTODOS DE ERRO (As três assinaturas mais comuns) ---
    @Override public void error(Exception e) { log.error("Exception IBKR: {}", e.getMessage(), e); }
    @Override public void error(String msg) { log.error("String Error IBKR: {}", msg); }
    // Assinatura obsoleta (depende da sua versão do JAR)
    @Override public void error(int var1, long var2, int var4, String var5, String var6) { /* Vazio */ }

    // --- CALLBACKS VAZIOS RESTANTES (Necessário para Compilação) ---

    @Override public void historicalDataUpdate(int reqId, Bar bar) { /* Vazio */ }
    @Override public void historicalData(int reqId, Bar bar) { /* Vazio */ }

    @Override
    public void scannerParameters(String s) {

    }

    @Override
    public void scannerData(int i, int i1, ContractDetails contractDetails, String s, String s1, String s2, String s3) {

    }

    @Override
    public void scannerDataEnd(int i) {

    }

    @Override
    public void realtimeBar(int i, long l, double v, double v1, double v2, double v3, Decimal decimal, Decimal decimal1, int i1) {

    }

    @Override
    public void currentTime(long l) {

    }

    @Override
    public void fundamentalData(int i, String s) {

    }

    @Override
    public void deltaNeutralValidation(int i, DeltaNeutralContract deltaNeutralContract) {

    }

    @Override
    public void tickSnapshotEnd(int i) {

    }

    @Override
    public void marketDataType(int i, int i1) {

    }

    @Override public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) { /* Vazio */ }
    @Override public void openOrderEnd() { /* Vazio */ }
    @Override public void updateAccountTime(String var1) { /* Vazio */ }
    @Override public void accountDownloadEnd(String var1) { /* Vazio */ }
    @Override public void tickPrice(int var1, int var2, double var3, TickAttrib var5) { /* Vazio */ }

    @Override
    public void updateMktDepth(int i, int i1, int i2, int i3, double v, Decimal decimal) {

    }

    @Override
    public void updateMktDepthL2(int i, int i1, String s, int i2, int i3, double v, Decimal decimal, boolean b) {

    }

    @Override
    public void updateNewsBulletin(int i, int i1, String s, String s1) {

    }

    @Override public void commissionAndFeesReport(CommissionAndFeesReport var1) { /* Vazio */ }
    @Override public void accountSummary(int var1, String var2, String var3, String var4, String var5) { /* Vazio */ }
    @Override public void accountSummaryEnd(int var1) { /* Vazio */ }
    @Override public void execDetailsEnd(int i) {}
    @Override
    public void verifyMessageAPI(String s) {

    }

    @Override
    public void verifyCompleted(boolean b, String s) {

    }

    @Override
    public void verifyAndAuthMessageAPI(String s, String s1) {

    }

    @Override
    public void verifyAndAuthCompleted(boolean b, String s) {

    }

    @Override public void tickSize(int var1, int var2, Decimal var3) { /* Vazio */ }
    @Override public void tickOptionComputation(int var1, int var2, int var3, double var4, double var6, double var8, double var10, double var12, double var14, double var16, double var18) { /* Vazio */ }
    @Override public void tickGeneric(int var1, int var2, double var3) { /* Vazio */ }
    @Override public void tickString(int var1, int var2, String var3) { /* Vazio */ }
    @Override public void tickEFP(int var1, int var2, double var3, String var5, double var6, int var8, String var9, double var10, double var12) { /* Vazio */ }
    @Override public void positionMulti(int var1, String var2, String var3, Contract var4, Decimal var5, double var6) { /* Vazio */ }
    @Override public void positionMultiEnd(int var1) { /* Vazio */ }
    @Override public void accountUpdateMulti(int var1, String var2, String var3, String var4, String var5, String var6) { /* Vazio */ }
    @Override public void accountUpdateMultiEnd(int var1) { /* Vazio */ }
    @Override public void securityDefinitionOptionalParameter(int var1, String var2, int var3, String var4, String var5, Set<String> var6, Set<Double> var7) { /* Vazio */ }
    @Override public void securityDefinitionOptionalParameterEnd(int var1) { /* Vazio */ }
    @Override public void softDollarTiers(int var1, SoftDollarTier[] var2) { /* Vazio */ }
    @Override public void familyCodes(FamilyCode[] var1) { /* Vazio */ }
    @Override public void symbolSamples(int var1, ContractDescription[] var2) { /* Vazio */ }
    @Override public void mktDepthExchanges(DepthMktDataDescription[] var1) { /* Vazio */ }
    @Override public void tickNews(int var1, long var2, String var4, String var5, String var6, String var7) { /* Vazio */ }
    @Override public void smartComponents(int var1, Map<Integer, Map.Entry<String, Character>> var2) { /* Vazio */ }
    @Override public void tickReqParams(int var1, double var2, String var4, int var5) { /* Vazio */ }
    @Override public void newsProviders(NewsProvider[] var1) { /* Vazio */ }
    @Override public void newsArticle(int var1, int var2, String var3) { /* Vazio */ }
    @Override public void historicalNews(int var1, String var2, String var3, String var4, String var5) { /* Vazio */ }
    @Override public void historicalNewsEnd(int var1, boolean var2) { /* Vazio */ }
    @Override public void headTimestamp(int var1, String var2) { /* Vazio */ }
    @Override public void histogramData(int var1, List<HistogramEntry> var2) { /* Vazio */ }
    @Override public void rerouteMktDataReq(int var1, int var2, String var3) { /* Vazio */ }
    @Override public void rerouteMktDepthReq(int var1, int var2, String var3) { /* Vazio */ }
    @Override public void marketRule(int var1, PriceIncrement[] var2) { /* Vazio */ }
    @Override public void pnl(int var1, double var2, double var4, double var6) { /* Vazio */ }
    @Override public void pnlSingle(int var1, Decimal var2, double var3, double var5, double var7, double var9) { /* Vazio */ }
    @Override public void historicalTicks(int var1, List<HistoricalTick> var2, boolean var3) { /* Vazio */ }
    @Override public void historicalTicksBidAsk(int var1, List<HistoricalTickBidAsk> var2, boolean var3) { /* Vazio */ }
    @Override public void historicalTicksLast(int var1, List<HistoricalTickLast> var2, boolean var3) { /* Vazio */ }
    @Override public void tickByTickAllLast(int var1, int var2, long var3, double var5, Decimal var7, TickAttribLast var8, String var9, String var10) { /* Vazio */ }
    @Override public void tickByTickBidAsk(int var1, long var2, double var4, double var6, Decimal var8, Decimal var9, TickAttribBidAsk var10) { /* Vazio */ }
    @Override public void tickByTickMidPoint(int var1, long var2, double var4) { /* Vazio */ }
    @Override public void orderBound(long var1, int var3, int var4) { /* Vazio */ }
    @Override public void completedOrder(Contract var1, Order var2, OrderState var3) { /* Vazio */ }
    @Override public void completedOrdersEnd() { /* Vazio */ }
    @Override public void replaceFAEnd(int var1, String var2) { /* Vazio */ }
    @Override public void wshMetaData(int var1, String var2) { /* Vazio */ }
    @Override public void wshEventData(int var1, String var2) { /* Vazio */ }
    @Override public void historicalSchedule(int var1, String var2, String var3, String var4, List<HistoricalSession> var5) { /* Vazio */ }
    @Override public void userInfo(int var1, String var2) { /* Vazio */ }
    @Override public void currentTimeInMillis(long var1) { /* Vazio */ }

    @Override
    public void orderStatusProtoBuf(OrderStatusProto.OrderStatus orderStatus) {

    }

    @Override public void openOrderProtoBuf(OpenOrderProto.OpenOrder var1) { /* Vazio */ }
    @Override public void openOrdersEndProtoBuf(OpenOrdersEndProto.OpenOrdersEnd var1) { /* Vazio */ }
    @Override public void errorProtoBuf(ErrorMessageProto.ErrorMessage var1) { /* Vazio */ }
    @Override public void execDetailsProtoBuf(ExecutionDetailsProto.ExecutionDetails var1) { /* Vazio */ }
    @Override public void execDetailsEndProtoBuf(ExecutionDetailsEndProto.ExecutionDetailsEnd var1) { /* Vazio */ }
    @Override public void connectionClosed() { log.error("🔌 Conexão fechada inesperadamente. Ativando reconexão."); }
    @Override public void connectAck() { log.info("Connect Ack received."); }
    @Override public void managedAccounts(String accountsList) { log.info("Contas Gerenciadas recebidas: {}", accountsList); }

    @Override
    public void receiveFA(int i, String s) {

    }

    @Override public void displayGroupList(int var1, String var2) { /* Vazio */ }
    @Override public void displayGroupUpdated(int var1, String var2) { /* Vazio */ }
}