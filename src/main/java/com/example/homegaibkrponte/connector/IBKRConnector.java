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
    private final ConcurrentHashMap<Integer, String> marketDataRequests = new ConcurrentHashMap<>();

    private final AtomicInteger currentAccountSummaryReqId = new AtomicInteger(-1);

    @Autowired
    private OrderIdManager orderIdManager;

    // NOVO MÉTODO: Requisição de Market Data (Implementação Completa)
    public void requestMarketData(String symbol) {
        // [LOG] Inicia o processo de requisição, essencial para rastreamento.
        log.info("➡️ Iniciando preparação da requisição de Market Data para {}.", symbol);

        Contract contract = new Contract();
        contract.symbol(symbol);
        contract.secType("STK");
        contract.exchange("SMART"); // Exchange Padrão
        contract.currency("USD"); // Moeda Americana (Ajuste conforme sua necessidade)

        int reqId = getNextReqId();

        // 🚨 CRÍTICO: Mapear ReqID ao Símbolo
        // Armazena o ID para que possamos rastrear o Ticker no callback tickPrice/tickSize.
        marketDataRequests.put(reqId, symbol);

        // Requisição de Market Data.
        client.reqMktData(reqId, contract, "", false, false, null);
        // [LOG] Loga o pedido para confirmar o fluxo.
        log.info("➡️ Requisitado Market Data para {} com reqId {}. Dados virão em tickPrice/tickSize.", symbol, reqId);
    }



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
            // Logs explicativos para rastrear o status da ordem.
            if ("Filled".equals(status) || "Partially Filled".equals(status)) {
                log.info("✅ [PONTE | TWS-IN | STATUS] Ordem IBKR {} | Status: {} | Preenchido: {}/{} | Preço Médio: {} | Execução confirmada pela IBKR.",
                        orderId, status.toUpperCase(), filled, filled.add(remaining), avgFillPrice);
            } else if ("Cancelled".equals(status) || "Rejected".equals(status) || "Inactive".equals(status)) {
                // 🛑 CORREÇÃO: Registra que o detalhe não foi fornecido no status.
                log.warn("❌ [PONTE | TWS-IN | STATUS] Ordem IBKR {} | Status: {} | Detalhe: {}. Ação de risco no TWS.",
                        orderId, status.toUpperCase(), whyHeld.isBlank() ? "Motivo não fornecido no orderStatus." : whyHeld);
            } else {
                log.debug("ℹ️ [PONTE | TWS-IN | STATUS] Ordem IBKR {} | Status: {}. Rastreando...",
                        orderId, status.toUpperCase());
            }

        } catch (Exception e) {
            // SEMPRE colocar try-catch para rastrear o que acontece no código [cite: 2025-10-18]
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

    @Override
    public void execDetails(int reqId, Contract contract, Execution execution) {
        // SEMPRE colocar try-catch para rastrear o que acontece no código [cite: 2025-10-18]
        try {
            log.info("💸 [PONTE | TWS-IN | EXECUÇÃO] Ordem IBKR {} EXECUTADA. Ação: {} {} {} @ {}. Exec ID: {}",
                    execution.orderId(), execution.side(), execution.shares(), contract.symbol(), execution.price(), execution.execId());

            // 1. Publica um evento de domínio (SINERGIA)
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
            log.debug("📢 Evento 'TradeExecutedEvent' publicado para a ordem {}. (Domínio Principal)", execution.orderId());

            // 2. Envia o relatório via webhook
            ExecutionReportDTO report = new ExecutionReportDTO(
                    execution.orderId(),
                    contract.symbol(),
                    execution.side(),
                    execution.shares().value(),
                    execution.price(),
                    "EXEC"
            );
            webhookNotifier.sendExecutionReport(report);
            log.info("📤 Relatório de Execução (BrokerID: {}) ENVIADO via Webhook ao sistema Principal (H.O.M.E.).", execution.orderId());

        } catch (Exception e) {
            log.error("💥 [PONTE | SINERGIA] Falha CRÍTICA ao processar/notificar Execution Report (ID {}).", execution.orderId(), e);
        }
    }


    /**
     * ✅ Implementação CRÍTICA com 5 parâmetros (Log Coerente).
     * Mapeamento dos parâmetros: ID=var1, CÓDIGO=var4, MENSAGEM=var5.
     * Filtra status de sistema para INFO/WARN e reserva ERROR para rejeições reais (201, 10243).
     */
    // Dentro da sua classe IBKRConnector.java (pacote com.example.homegaibkrponte.connector)

    @Override
    public void error(int var1, long var2, int var4, String var5, String var6) {

        // Mapeamento dos argumentos para nomes legíveis e de diagnóstico:
        final int id = var1; // Broker Order ID (se id > 0)
        final int errorCode = var4; // O CÓDIGO NUMÉRICO (ex: 201)
        final String errorMsg = var5; // A Mensagem principal
        final String extendedMsg = var6; // Detalhe

        // Log de diagnóstico completo (mantido para rastreabilidade)
        log.debug("🔍 [DIAGNÓSTICO TWS RAW] ID: {} | CÓDIGO: {} | MENSAGEM: {} | Detalhe: {}",
                id, errorCode, errorMsg, extendedMsg);

        // 1. Lógica para rebaixar STATUS e AVISOS para INFO/WARN (Coerência)
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
        // 2. Lógica para ERROS REAIS (id > 0)
        else {
            // Erros que causam rejeição funcional (201, 10243, etc.)
            if (errorCode == 201 || errorCode == 10243) {
                log.error("🛑🛑🛑 [TWS-ERROR CRÍTICO ORDEM] ID: {} | CÓDIGO: {} | MENSAGEM: '{}'. AÇÃO IMEDIATA NECESSÁRIA.",
                        id, errorCode, errorMsg);

                // 🚨 AÇÃO CRÍTICA (SINERGIA)
                // A Ponte notifica a rejeição crítica ao sistema Principal.
                // O ID (var1) é o Broker Order ID da ordem rejeitada.
                try {
                    // ASSUMIDO: O WebhookNotifierService possui o método sendOrderRejection.
                    webhookNotifier.sendOrderRejection(id, errorCode, errorMsg);
                    log.info("📤 Relatório de Rejeição (BrokerID: {}) ENVIADO via Webhook ao sistema Principal.", id);
                } catch (Exception e) {
                    log.error("❌ Falha ao notificar a rejeição da ordem {} ao Principal: {}", id, e.getMessage(), e);
                }

            } else {
                // Outros erros de ordem (ex: 2109 em ordem específica é tratado como AVISO)
                log.warn("🟡 [TWS-IN] AVISO DE ORDEM {}: Código {}, Mensagem: '{}'", id, errorCode, errorMsg);
            }
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

    // Exemplo de como você PODE estar tratando as tags atualmente (e gerando os avisos)
// Este é o método que precisa ser ajustado:

    // MÉTODO PRONTO PARA SUBSTITUIR: IBKRConnector.updateAccountValue()

    @Override
    public void updateAccountValue(String key, String value, String currency, String accountName) {

        // Lista de tags conhecidas que são Strings/Booleanos e NUNCA devem ser submetidas ao parsing numérico.
        if ("AccountCode".equals(key) || "AccountOrGroup".equals(key) ||
                "AccountReady".equals(key) || "AccountType".equals(key) ||
                "Currency".equals(key) || "RealCurrency".equals(key) ||
                key.contains("TradingType") || key.contains("SegmentTitle") ||
                key.contains("SettledCashByDate") || key.contains("DayTradingStatus-S") ||
                "NLVAndMarginInReview".equals(key) || "WhatIfPMEnabled".equals(key)) {

            // 1. Tags Conhecidas NÃO Numéricas (Apenas loga e sai, limpando o DEBUG)
//            log.debug("ℹ️ [IBKR TWS INFO] Tag de informação recebida. Chave: '{}', Valor: '{}', Moeda: '{}'", key, value, currency);
            return;
        }

        // --- 2. Bloco CRÍTICO: Tags Numéricas de Liquidez e Saldo ---

        // Tags CRÍTICAS que DEVEM ser convertidas para BigDecimal e enviadas ao PortfolioService.
        if ("BuyingPower".equalsIgnoreCase(key) ||
                "AvailableFunds".equalsIgnoreCase(key) || // Tag comum para fundos disponíveis
                "NetLiquidation".equalsIgnoreCase(key) || // Patrimônio líquido (também importante)
                "CashBalance".equalsIgnoreCase(key) ||
                "GrossPositionValue".equalsIgnoreCase(key))
        {
            // Pré-processamento: Limpa o valor removendo tudo que não seja dígito, ponto decimal ou sinal negativo.
            String cleanedValue = value.replaceAll("[^0-9\\.\\-]", "");

            if (cleanedValue.isEmpty() || value.matches(".*[a-zA-Z].*")) {
                log.debug("🔍 [IBKR INFO] Valor numérico crítico veio vazio/invalido para {}: {}", key, value);
                return;
            }

            try {
                BigDecimal numericValue = new BigDecimal(cleanedValue);

                // ✅ AÇÃO CRÍTICA (SINERGIA): O Connector converte a String e chama o Service com BigDecimal.
                // Se esta chamada for bem-sucedida, o LivePortfolioService libera a 'accountSyncLatch'.
                portfolioService.updateAccountValue(key, numericValue);

            } catch (NumberFormatException e) {
                log.error("❌ [PONTE | ERRO] Falha CRÍTICA na conversão para tag {}. Valor: {}. Ignorado.", key, value, e);
            }
            return; // Sai após processar a tag numérica conhecida
        }

        // --- 3. Outras Tags Numéricas (Fallback) ---
        else {
            // Tenta fazer o parse para rastreamento, mas não envia para o PortfolioService (não é crítico para liquidez/estado)
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
    }

    /**
     * 🔌 Desconecta do TWS/IB Gateway e realiza a limpeza de estado.
     * Ação necessária para evitar que requisições antigas vazem ou causem inconsistências
     * em caso de reconexão ou desligamento, conforme a regra de pensar no ecossistema.
     */
    @Override
    public void disconnect() {
        // 1. Verifica a conexão antes de tentar desconectar
        if (client.isConnected()) {
            log.info("➡️ Iniciando desconexão controlada do TWS/IB Gateway...");

            // 2. Envia o comando de desconexão para o TWS/IB
            client.eDisconnect();

            log.warn("🔌 Desconectado do TWS/IB Gateway.");

            // 3. 🚨 AÇÃO CRÍTICA: Limpeza do Estado (Recurso Concorrente)
            // Limpar o mapa de requisições de Market Data é essencial para evitar vazamento de estado
            // e garantir que, ao reconectar, a lista de Market Data seja construída do zero.
            marketDataRequests.clear();
            log.debug("🧹 MarketDataRequests limpado. Estado da Ponte pronto para shutdown ou reconexão.");
        } else {
            log.info("ℹ️ TWS/IB Gateway já estava desconectado. Nenhuma ação necessária.");
        }
    }
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
    /**
     * 🚨 CORREÇÃO CRÍTICA: Implementação do callback de Preço (Market Tick).
     * Captura o preço do TWS e o transmite para o sistema principal.
     * Respeita a assinatura exata: @Override public void tickPrice(int var1, int var2, double var3, TickAttrib var5)
     */
    @Override
    public void tickPrice(int var1, int var2, double var3, TickAttrib var5) {
        // Usamos os nomes descritivos internamente para clareza e logs.
        final int tickerId = var1;
        final int field = var2;
        final double price = var3;

        // [TRY-CATCH/LOG] Validação básica de dados (preços inválidos)
        if (price <= 0 || price == Double.MAX_VALUE) {
            // [LOG] Adiciona try-catch e log explicativo para rastrear.
            log.trace("⚠️ TICK PRICE descartado: Preço inválido ({}) para ID: {}.", price, tickerId);
            return;
        }

        // 1. Obtém o símbolo usando o mapeamento CRÍTICO (marketDataRequests foi adicionado)
        String symbol = marketDataRequests.get(tickerId);
        if (symbol == null) {
            log.warn("⚠️ TICK PRICE recebido para ID não rastreado: {}. Ignorado.", tickerId);
            return;
        }

        // 2. Filtra apenas os ticks de preço mais relevantes (BID, ASK, LAST)
        if (field == TickType.BID.index() || field == TickType.ASK.index() || field == TickType.LAST.index()) {
            BigDecimal currentPrice = BigDecimal.valueOf(price);

            // [LOG] Loga o tick recebido para rastreamento.
            log.debug("📢 [TWS-OUT] TICK PRICE recebido ({} | {}): R$ {}", symbol, TickType.getField(field), currentPrice);

            // 3. 🚨 AÇÃO CRÍTICA: Notifica o sistema principal sobre o novo preço (Sinergia/Produção)
            // A Bridge envia o Ticker e o Preço para o H.O.M.E. (Projeto Principal)
            webhookNotifier.sendMarketTick(symbol, currentPrice);
        }
    }


    @Override
    public void updateMktDepth(int i, int i1, int i2, int i3, double v, Decimal decimal) {

    }

    @Override
    public void updateMktDepthL2(int i, int i1, String s, int i2, int i3, double v, Decimal decimal, boolean b) {

    }

    @Override
    public void updateNewsBulletin(int i, int i1, String s, String s1) {

    }

    public int requestAccountSummarySnapshot() {
        // ✅ 1. Cancelar o anterior para evitar o ERRO 322 (Limite de Requisição Excedido)
        cancelAccountSummary();

        int reqId = getNextReqId();

        // ✅ 2. Rastreia o novo ID
        currentAccountSummaryReqId.set(reqId);

        // ✅ 3. CORRIGIDO: O grupo unificado deve ser "All" para evitar o ERRO 321.
        client.reqAccountSummary(reqId, "All", "All");

        log.info("➡️ [PONTE | SNAPSHOT] Requisitado Account Summary com reqId {}. (Usando Grupo: 'All').", reqId);
        return reqId;
    }

    /**
     * ✅ NOVO MÉTODO: Cancela a última requisição de resumo de conta ativa.
     * CRÍTICO para a estabilidade e adesão às regras do TWS.
     */
    public void cancelAccountSummary() {
        int reqId = currentAccountSummaryReqId.getAndSet(-1); // Reseta o ID
        if (reqId > 0) {
            client.cancelAccountSummary(reqId);
            log.info("➡️ [PONTE | SNAPSHOT] Cancelada requisição anterior de Account Summary (reqId {}).", reqId);
        }
    }

    @Override public void commissionAndFeesReport(CommissionAndFeesReport var1) { /* Vazio */ }
    @Override
    public void accountSummary(int reqId, String account, String tag, String value, String currency) {
        try {
            // Reutiliza a lógica de tratamento de valores
            BigDecimal accountValue;
            try {
                accountValue = new BigDecimal(value.replaceAll(",", ""));
            } catch (NumberFormatException e) {
                log.debug("⚠️ Valor não numérico recebido na AccountSummary para tag '{}'. Ignorado.", tag);
                return;
            }

            // ✅ SINERGIA: Passa todos os valores para o LivePortfolioService para cache.
            portfolioService.updateAccountValue(tag, accountValue);
            log.debug("📊 [PONTE | SNAPSHOT-IN] Account Summary Recebido: {} = R$ {}", tag, accountValue);

        } catch (Exception e) {
            log.error("💥 [PONTE | SNAPSHOT] Erro ao processar Account Summary. Tag: {}", tag, e);
        }
    }

//    public int requestAccountSummarySnapshot() {
//        int reqId = getNextReqId();
//
//        // CORREÇÃO CRÍTICA DO ERRO 321: O grupo unificado deve ser "All" e as tags "All"
//        // (A TWS API espera "All" para o grupo se você não estiver usando um grupo de alocação unificado).
//        client.reqAccountSummary(reqId, "All", "All");
//
//        log.info("➡️ [PONTE | SNAPSHOT] Requisitado Account Summary com reqId {}. (Usando Grupo: 'All').", reqId);
//        return reqId;
//    }

    @Override
    public void accountSummaryEnd(int reqId) {
        log.info("✅ [PONTE | SNAPSHOT-END] Fim do Account Summary para reqId {}.", reqId);
        // 🛑 CORREÇÃO: Cancela o rastreamento localmente após o TWS sinalizar o fim
        currentAccountSummaryReqId.compareAndSet(reqId, -1);
    }
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

    /**
     * 🚨 CORREÇÃO CRÍTICA: Implementação do callback de Tamanho (Volume/Liquidez).
     * Essencial para lógicas de liquidez e para manter a Ponte funcional em Produção.
     * * Assinatura respeitada: @Override public void tickSize(int var1, int var2, Decimal var3)
     */
    @Override
    public void tickSize(int var1, int var2, Decimal var3) {
        // Usamos nomes descritivos para clareza interna e nos logs.
        final int tickerId = var1;
        final int field = var2;
        final Decimal size = var3;

        // 1. Obtém o símbolo usando o mapeamento CRÍTICO (marketDataRequests)
        String symbol = marketDataRequests.get(tickerId);

        // Se o símbolo não for rastreado, a requisição é ignorada, mas não quebra.
        if (symbol == null) return;

        // 2. Filtra para ticks de volume importantes (Volume, Bid Size, Ask Size)
        // O size.value() fornece a quantidade.
        if (field == TickType.VOLUME.index() || field == TickType.BID_SIZE.index() || field == TickType.ASK_SIZE.index()) {

            // [LOG] Loga o volume/tamanho de forma explícita para rastreamento (try-catch/logs explicativos).
            log.trace("📢 [TWS-OUT] TICK SIZE recebido ({} | {}): Tamanho: {}", symbol, TickType.getField(field), size.value());

            // Nota: Não é necessário notificar o Principal com o WebhookNotifier para TICK SIZE neste momento,
            // pois o Projeto Principal (H.O.M.E.) geralmente consome volume/liquidez do cache de Market Data,
            // e o foco é no tickPrice para preço de execução.
        }
    }


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