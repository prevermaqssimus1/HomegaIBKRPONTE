package com.example.homegaibkrponte.connector;

import com.example.homegaibkrponte.connector.mapper.IBKRMapper;
import com.example.homegaibkrponte.data.MarketDataProvider;
import com.example.homegaibkrponte.dto.ExecutionReportDto;
import com.example.homegaibkrponte.exception.MarginRejectionException;
import com.example.homegaibkrponte.exception.OrdemFalhouException;
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
 * É o coração da PONTE e gerencia a conexão e os callbacks.
 *
 * NOTA: Esta é a classe principal da **PONTE** que interage com a API IBKR.
 */
@Service
@Slf4j
public class IBKRConnector implements MarketDataProvider, EWrapper {

    // ==========================================================
    // DECLARAÇÕES DE CAMPO
    // ==========================================================
    private final IBKRProperties ibkrProps;
    private final WebhookNotifierService webhookNotifier;
    private final AtomicReference<BigDecimal> buyingPowerCache = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> excessLiquidityCache = new AtomicReference<>(BigDecimal.ZERO);
    private final List<PositionDTO> tempPositions = new ArrayList<>();
    private final LivePortfolioService portfolioService;
    private final ApplicationEventPublisher eventPublisher;
    private final ConcurrentHashMap<Integer, String> marketDataRequests = new ConcurrentHashMap<>();

    private final AtomicInteger currentAccountSummaryReqId = new AtomicInteger(-1);

    private final OrderIdManager orderIdManager;
    private final IBKRMapper ibkrMapper;

    private EClientSocket client;
    private EReaderSignal readerSignal;
    private final AtomicInteger nextValidId = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, CompletableFuture<List<Candle>>> pendingHistoricalData = new ConcurrentHashMap<>();
    private final CountDownLatch connectionLatch = new CountDownLatch(1);


    // ==========================================================
    // CONSTRUTOR
    // ==========================================================
    @Autowired
    public IBKRConnector(IBKRProperties props,
                         WebhookNotifierService notifier,
                         LivePortfolioService portfolioService,
                         ApplicationEventPublisher eventPublisher,
                         OrderIdManager orderIdManager,
                         IBKRMapper ibkrMapper) {
        this.ibkrProps = props;
        this.webhookNotifier = notifier;
        this.portfolioService = portfolioService;
        this.eventPublisher = eventPublisher;
        this.orderIdManager = orderIdManager;
        this.readerSignal = new EJavaSignal();
        this.client = new EClientSocket(this, readerSignal);
        this.ibkrMapper = ibkrMapper;
        log.info("ℹ️ [Ponte IBKR] Inicializador concluído. Mappers e Serviços injetados (Sinergia OK).");
    }


    // --- MÉTODOS AUXILIARES PÚBLICOS ---
    public int getNextReqId() { return nextValidId.getAndIncrement(); }
    public EClientSocket getClient() { return client; }
    public BigDecimal getBuyingPowerCache() { return buyingPowerCache.get(); }
    public BigDecimal getExcessLiquidityCache() {return excessLiquidityCache.get();}
    public String getAccountId() {
        return "DUN652604";
    }

    // NOVO MÉTODO: Requisição de Market Data (Implementação Completa)
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

    /**
     * Envia a ordem principal para a Ponte IBKR.
     * @param ordemPrincipal Ordem a ser enviada.
     */
    public void enviarOrdem(com.example.homegaibkrponte.model.Order ordemPrincipal) throws MarginRejectionException, OrdemFalhouException {
        try {
            // 1. Uso dos Mappers (SINERGIA)
            com.ib.client.Order ibkrOrder = ibkrMapper.toIBKROrder(ordemPrincipal);
            com.ib.client.Contract contract = ibkrMapper.toContract(ordemPrincipal);

            int orderId = ibkrOrder.orderId();

            // 2. Uso do twsClient
            log.info("➡️ [Ponte IBKR] Enviando ordem ID: {} | Ação: {} | Tipo: {} | Símbolo: {}",
                    orderId, ibkrOrder.action(), ibkrOrder.orderType(), contract.symbol());

            client.placeOrder(orderId, contract, ibkrOrder);

            log.info("✅ [Ponte IBKR] Ordem ID: {} enviada com sucesso.", orderId);

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

    @Override public void openOrder(int orderId, Contract contract, Order order, OrderState orderState) {
        try {
            log.info("ℹ️ [PONTE | TWS-IN | OPEN] Ordem {} aberta. Ativo: {} {} @ {}. Status TWS: {}.",
                    orderId, order.action(), order.totalQuantity(), contract.symbol(), orderState.status());
        } catch (Exception e) {
            log.error("💥 [PONTE | TWS-IN] Erro ao processar openOrder para ID {}.", orderId, e);
        }
    }

    /**
     * Recebe a confirmação de execução do IBKR.
     */
    // ... dentro da classe IBKRConnector ...

    @Override
    public void execDetails(int reqId, Contract contract, Execution execution) {
        // Bloco try-catch obrigatório para rastrear falhas na execução
        try {
            // Logs de rastreamento do TWS-IN
            log.info("💸 [PONTE | TWS-IN | EXECUÇÃO] Ordem IBKR {} EXECUTADA. Ação: {} {} {} @ {}. Exec ID: {}",
                    execution.orderId(), execution.side(), execution.shares().longValue(), contract.symbol(), execution.price(), execution.execId());

            // 1. Publica um evento de domínio (SINERGIA com o Principal)
            // Agora, garantimos que execution.shares().longValue() seja convertido para BigDecimal.
            TradeExecutedEvent event = new TradeExecutedEvent(
                    contract.symbol(),
                    execution.side(),
                    // ✅ CORREÇÃO CRÍTICA: Converte long para BigDecimal, respeitando o modelo do evento.
                    BigDecimal.valueOf(execution.shares().longValue()),
                    BigDecimal.valueOf(execution.price()),
                    LocalDateTime.now(),
                    "LIVE",
                    String.valueOf(execution.orderId())
            );
            eventPublisher.publishEvent(event);
            log.debug("📢 Evento 'TradeExecutedEvent' publicado para a ordem {}. (Domínio Principal)", execution.orderId());

            // 2. Envia o relatório via webhook
            // Mantendo a quantidade como (int) longValue() para o DTO (ExecutionReportDto)
            ExecutionReportDto report = new ExecutionReportDto(
                    execution.orderId(),
                    contract.symbol(),
                    execution.side(),
                    (int) execution.shares().longValue(),
                    java.math.BigDecimal.valueOf(execution.price()),
                    java.time.LocalDateTime.now(),
                    execution.execId()
            );
            webhookNotifier.sendExecutionReport(report);
            log.info("📤 Relatório de Execução (BrokerID: {}) ENVIADO via Webhook ao sistema Principal (H.O.M.E.).", execution.orderId());

        } catch (Exception e) {
            log.error("💥 [PONTE | SINERGIA] Falha CRÍTICA ao processar/notificar Execution Report (ID {}). Rastreando erro na extração de dados/conversão.", execution.orderId(), e);
        }
    }

// ... restante da classe IBKRConnector ...


    /**
     * ✅ Implementação CRÍTICA do error do EWrapper.
     */
    @Override
    public void error(int var1, long var2, int var4, String var5, String var6) {

        final int id = var1;
        final int errorCode = var4;
        final String errorMsg = var5;
        final String extendedMsg = var6;

        log.debug("🔍 [DIAGNÓSTICO TWS RAW] ID: {} | CÓDIGO: {} | MENSAGEM: {} | Detalhe: {}",
                id, errorCode, errorMsg, extendedMsg);

        if (id < 0) {
            if (errorCode == 2104 || errorCode == 2158) {
                log.info("✅ [TWS-IN] STATUS DE CONEXÃO: Código {}, Mensagem: '{}'", errorCode, errorMsg);
            } else if (errorCode == 2107 || errorCode == 2109 || errorCode == 2100) {
                if (errorCode == 2100) {
                    log.info("ℹ️ [TWS-IN] INFO DE SISTEMA: Código 2100. Mensagem: 'Inscrição de dados de conta cancelada (Operação Normal da Ponte).'");
                } else {
                    log.warn("🟡 [TWS-IN] AVISO: Código {}, Mensagem: '{}'.", errorCode, errorMsg);
                }
            } else {
                log.error("❌ [TWS-IN] ERRO DE SISTEMA: Código {}, Mensagem: '{}'", errorCode, errorMsg);
            }
        }
        else {
            if (errorCode == 201 || errorCode == 10243) {
                log.error("🛑🛑🛑 [TWS-ERROR CRÍTICO ORDEM] ID: {} | CÓDIGO: {} | MENSAGEM: '{}'. AÇÃO IMEDIATA NECESSÁRIA.",
                        id, errorCode, errorMsg);

                try {
                    webhookNotifier.sendOrderRejection(id, errorCode, errorMsg);
                    log.info("📤 Relatório de Rejeição (BrokerID: {}) ENVIADO via Webhook ao sistema Principal.", id);
                } catch (Exception e) {
                    log.error("❌ Falha ao notificar a rejeição da ordem {} ao Principal: {}", id, e.getMessage(), e);
                }

            } else {
                log.warn("🟡 [TWS-IN] AVISO DE ORDEM {}: Código {}, Mensagem: '{}'", id, errorCode, errorMsg);
            }
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

            // ✅ AJUSTE: Incluindo ExcessLiquidity aqui
            if ("BuyingPower".equalsIgnoreCase(key) ||
                    "AvailableFunds".equalsIgnoreCase(key) ||
                    "NetLiquidation".equalsIgnoreCase(key) ||
                    "CashBalance".equalsIgnoreCase(key) ||
                    "GrossPositionValue".equalsIgnoreCase(key) ||
                    "ExcessLiquidity".equalsIgnoreCase(key))
            {
                String cleanedValue = value.replaceAll("[^0-9\\.\\-]", "");

                if (cleanedValue.isEmpty() || value.matches(".*[a-zA-Z].*")) {
                    log.debug("🔍 [IBKR INFO] Valor numérico crítico veio vazio/invalido para {}: {}", key, value);
                    return;
                }

                try {
                    BigDecimal numericValue = new BigDecimal(cleanedValue);
                    portfolioService.updateAccountValue(key, numericValue);

                    // Atualiza o cache interno da Ponte para os Controllers
                    if ("BuyingPower".equalsIgnoreCase(key)) {
                        buyingPowerCache.set(numericValue);
                        log.trace("📈 [PONTE | CACHE] BuyingPower atualizado no cache da Ponte para: R$ {}", numericValue);
                    }

                    // ✅ AÇÃO CRÍTICA: Atualiza o cache de Excess Liquidity (Essencial para o Principal)
                    if ("ExcessLiquidity".equalsIgnoreCase(key)) {
                        // Utiliza o novo cache que o LiquidityController irá expor
                        excessLiquidityCache.set(numericValue);
                        log.info("📈 [PONTE | CACHE] ExcessLiquidity atualizado no cache da Ponte para: R$ {}", numericValue.toPlainString());
                    }

                } catch (NumberFormatException e) {
                    log.error("❌ [PONTE | ERRO] Falha CRÍTICA na conversão para tag {}. Valor: {}. Ignorado. Rastreando.", key, value, e);
                }
                return;
            }
            // --- Outras Tags Numéricas (Fallback) ---
            else {
                String cleanedValue = value.replaceAll("[^0-9\\.\\-]", "");
                if (cleanedValue.isEmpty() || value.matches(".*[a-zA-Z].*")) {
                    log.trace("🔍 [IBKR INFO] Tag desconhecida ou string esperada: {} | Valor: {}", key, value);
                    return;
                }

                try {
                    new BigDecimal(cleanedValue);
                    log.trace("📈 [IBKR INFO] Tag numérica desconhecida processada. Chave: '{}', Valor: {}", key, cleanedValue);
                } catch (NumberFormatException e) {
                    log.debug("🔍 [IBKR INFO] Tag desconhecida que falhou na conversão: {} | Valor: {}", key, value);
                }
            }
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
    // MÉTODOS EWrapper (CALLBACKS E AUXILIARES)
    // ==========================================================

    @Override
    public void nextValidId(int orderId) {
        try {
            log.info("✅ Conexão estabelecida com sucesso. Próximo ID de Ordem Válido: {}", orderId);
            orderIdManager.initializeOrUpdate(orderId);
            connectionLatch.countDown();
        } catch (Exception e) {
            log.error("💥 [Ponte IBKR] Falha ao processar nextValidId {}. Rastreando.", orderId, e);
        }
    }

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

            if (field == TickType.BID.index() || field == TickType.ASK.index() || field == TickType.LAST.index()) {
                BigDecimal currentPrice = BigDecimal.valueOf(price);
                log.debug("📢 [TWS-OUT] TICK PRICE recebido ({} | {}): R$ {}", symbol, TickType.getField(field), currentPrice);
                webhookNotifier.sendMarketTick(symbol, currentPrice);
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

    @Override public void commissionAndFeesReport(CommissionAndFeesReport var1) {}
    @Override
    public void accountSummary(int reqId, String account, String tag, String value, String currency) {
        try {
            BigDecimal accountValue;
            try {
                accountValue = new BigDecimal(value.replaceAll(",", ""));
            } catch (NumberFormatException e) {
                log.debug("⚠️ Valor não numérico recebido na AccountSummary para tag '{}'. Ignorado.", tag);
                return;
            }

            portfolioService.updateAccountValue(tag, accountValue);
            log.debug("📊 [PONTE | SNAPSHOT-IN] Account Summary Recebido: {} = R$ {}", tag, accountValue);

        } catch (Exception e) {
            log.error("💥 [PONTE | SNAPSHOT] Erro ao processar Account Summary. Tag: {}", tag, e);
        }
    }

    @Override
    public void accountSummaryEnd(int reqId) {
        try {
            log.info("✅ [PONTE | SNAPSHOT-END] Fim do Account Summary para reqId {}.", reqId);
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
    @Override public void orderStatusProtoBuf(OrderStatusProto.OrderStatus orderStatus) {}
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