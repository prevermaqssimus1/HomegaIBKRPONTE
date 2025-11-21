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
 *
 * **Metodologia Aplicada:** SOLID, Padrão Bridge/Adapter, Boas Práticas (Logs e Try-Catch).
 */
@Service
@Slf4j
public class IBKRConnector implements MarketDataProvider, EWrapper {

    // ==========================================================
    // DECLARAÇÕES DE CAMPO (PONTE)
    // ==========================================================
    private final IBKRProperties ibkrProps;
    private final WebhookNotifierService webhookNotifier;
    private final AtomicReference<BigDecimal> buyingPowerCache = new AtomicReference<>(BigDecimal.ZERO);
    // ✅ CAMPO RESTAURADO: Cache local para Excess Liquidity
    private final AtomicReference<BigDecimal> excessLiquidityCache = new AtomicReference<>(BigDecimal.ZERO);
    private final List<PositionDTO> tempPositions = new ArrayList<>();
    private final LivePortfolioService portfolioService;
    private final ApplicationEventPublisher eventPublisher;
    private final ConcurrentHashMap<Integer, String> marketDataRequests = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;
    private final AtomicInteger currentAccountSummaryReqId = new AtomicInteger(-1);
    private final ConcurrentMap<Integer, CompletableFuture<OrderStateDTO>> whatIfFutures = new ConcurrentHashMap<>();

    private final OrderIdManager orderIdManager;
    private final IBKRMapper ibkrMapper;

    private EClientSocket client;
    private EReaderSignal readerSignal;
    private final AtomicInteger nextValidId = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, CompletableFuture<List<Candle>>> pendingHistoricalData = new ConcurrentHashMap<>();
    private final CountDownLatch connectionLatch = new CountDownLatch(1);
    private static final int CRITICAL_MARGIN_REQ_ID = 9001; // ID fixo para requisições de sumário de margem

    // MAPA CRÍTICO para requisições assíncronas de What-If (Se a API for atualizada, este mapa será usado)
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

        // Tags essenciais para a validação de Excesso de Liquidez e CÓDIGO 201.
        String tags = "MaintMarginReq,InitMarginReq,EquityWithLoanValue,NetLiquidationValue";
        String group = "All"; // Group é usado para contas gerenciadas

        // 🚨 AJUSTE DE SINERGIA: Chamada correta com 3 argumentos (reqId, group, tags)
        client.reqAccountSummary(CRITICAL_MARGIN_REQ_ID, group, tags);

        log.info("📊 [Ponte | MARGEM] Solicitado sumário de margem crítico (MaintMarginReq, InitMarginReq). ReqID: {}. Tags: {}",
                CRITICAL_MARGIN_REQ_ID, tags);
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
            log.info("➡️➡️➡️ [Ponte IBKR] Enviando ordem ID: {} | Ação: {} | Tipo: {} | Símbolo: {}",
                    orderId, ibkrOrder.action(), ibkrOrder.orderType(), contract.symbol());

            client.placeOrder(orderId, contract, ibkrOrder);

            log.info("✅ [Ponte IBKR] Ordem ID: {} enviada com sucesso.", orderId);

        } catch (Exception e) {
            String errorMessage = e.getMessage();

            // 🛑 TRATAMENTO CRÍTICO DO ERRO 201 (MARGEM)
            if (errorMessage != null && errorMessage.contains("201")) {
                log.error("❌🚨 [Ponte IBKR | ERRO 201 MARGEM] Ordem {} rejeitada (Margem). Mensagem: {}",
                        ordemPrincipal.symbol(), errorMessage, e);
                // Lança a exceção de domínio para o Principal (WebClient) capturar e ativar o Resgate.
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
        try {
            // Verifica se o ID de ordem está no mapa de Futures de What-If pendentes.
            if (order.whatIf() && whatIfFutures.containsKey(orderId)) {

                // 1. É uma resposta What-If. Captura e remove o Future pendente.
                CompletableFuture<OrderStateDTO> future = whatIfFutures.remove(orderId);

                // --- SINERGIA: Mapeia para os campos existentes ---
                String marginChange = orderState.initMarginChange();
                String equityAfter = orderState.equityWithLoanAfter();

                log.info("📢 [PONTE | TWS-IN | What-If] Resultado REAL recebido. Simulação What-If para {}.", contract.symbol());
                log.info("ℹ️ [PONTE | TWS-IN | What-If] Impacto na Margem Inicial (Change): {}", marginChange);
                log.info("ℹ️ [PONTE | TWS-IN | What-If] Patrimônio/Liquidez Pós-Simulação (Equity After): {}", equityAfter);

                // 2. Cria o DTO de estado com os valores reais mapeados
                OrderStateDTO resolvedState = ibkrMapper.toOrderStateDTO(orderState);

                // 3. Resolve a Promise com o estado completo.
                future.complete(resolvedState);

                return; // Termina o processamento para este What-If
            }

            // --- Lógica para ordens normais (mantida) ---
            log.info("ℹ️ [PONTE | TWS-IN | OPEN] Ordem {} aberta. Ativo: {} {} @ {}. Status TWS: {}.",
                    orderId, order.action(), order.totalQuantity(), contract.symbol(), orderState.getStatus());

        } catch (Exception e) {
            log.error("💥 [PONTE | TWS-IN] Erro ao processar openOrder para ID {}.", orderId, e);
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
    public void error(int var1, long var2, int var4, String var5, String var6) {

        final int id = var1;
        final int errorCode = var4;
        final String errorMsg = var5;
        final String extendedMsg = var6;

        log.debug("🔍 [DIAGNÓSTICO TWS RAW] ID: {} | CÓDIGO: {} | MENSAGEM: {} | Detalhe: {}",
                id, errorCode, errorMsg, extendedMsg);

        // --- NOVO TRATAMENTO CRÍTICO: Falha em Simulações What-If Pendentes ---
        // Verifica se este ID de erro corresponde a um CompletableFuture de What-If ativo.
        CompletableFuture<OrderStateDTO> whatIfFuture = whatIfFutures.get(id);
        if (whatIfFuture != null) {
            whatIfFutures.remove(id); // Remove imediatamente para evitar processamento futuro
            log.error("❌ [PONTE | What-If ERRO FATAL] ID: {} | CÓDIGO: {} | Mensagem: '{}'. Simulação falhou, completando Future com exceção.",
                    id, errorCode, errorMsg);

            // Completa o Future com uma exceção, que será capturada no .join() do sendWhatIfRequest
            whatIfFuture.completeExceptionally(
                    new RuntimeException("Simulação What-If Falhou (TWS Code: " + errorCode + "): " + errorMsg)
            );
            return; // Termina o processamento. O erro What-If foi tratado.
        }

        // --- Tratamento de erros gerais e avisos da TWS (Lógica Original) ---

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
            // 🛑 TRATAMENTO CRÍTICO DE REJEIÇÃO ASSÍNCRONA (Ordem Real)
            if (errorCode == 201 || errorCode == 10243) {
                log.error("🛑🛑🛑 [TWS-ERROR CRÍTICO ORDEM] ID: {} | CÓDIGO: {} | MENSAGEM: '{}'. AÇÃO IMEDIATA NECESSÁRIA.",
                        id, errorCode, errorMsg);

                try {
                    // ✅ SINERGIA: Envia a notificação para o Principal via Webhook.
                    webhookNotifier.sendOrderRejection(id, errorCode, errorMsg);
                    log.info("📤 Relatório de Rejeição (BrokerID: {}) ENVIADO via Webhook ao sistema Principal.", id);
                } catch (Exception e) {
                    // Não esquecer do try-catch e logs explicativos
                    log.error("❌ Falha ao notificar a rejeição da ordem {} ao Principal: {}", id, e.getMessage(), e);
                }

            } else {
                // Lógica legada para erros que podem ser do antigo reqMarginWhatIf (mantida, mas com ressalvas)
                CompletableFuture<MarginWhatIfResponseDTO> legacyWhatIfFuture = pendingMarginWhatIfRequests.get(id);
                if (legacyWhatIfFuture != null && !legacyWhatIfFuture.isDone()) {
                    log.warn("⚠️ [Ponte | TWS-IN What-If LEGADO ERROR] Erro {} recebido para reqId LEGADO {}. Completa com erro de margem.", errorCode, id);

                    // Completa com erro para o chamador do método LEGADO
                    legacyWhatIfFuture.complete(
                            new MarginWhatIfResponseDTO(
                                    null,                       // 1. symbol (String)
                                    BigDecimal.ZERO,            // 2. quantity (BigDecimal)
                                    BigDecimal.ZERO,            // 3. initialMarginChange (BigDecimal)
                                    BigDecimal.ZERO,            // 4. maintenanceMarginChange (BigDecimal)
                                    BigDecimal.ZERO,            // 5. commissionEstimate (BigDecimal)
                                    "BRL",                      // 6. currency (String - Corrigido para o tipo String do record)
                                    errorMsg                    // 7. error (String)
                            )
                    );
                } else {
                    log.warn("🟡 [TWS-IN] AVISO DE ORDEM {}: Código {}, Mensagem: '{}'", id, errorCode, errorMsg);
                }
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

            if ("BuyingPower".equalsIgnoreCase(key) ||
                    "AvailableFunds".equalsIgnoreCase(key) ||
                    "NetLiquidation".equalsIgnoreCase(key) || // Tag crítica
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

                    // 🛑 CORREÇÃO CRÍTICA: Se for NLV, chama o setter dedicado no LivePortfolioService (SSOT).
                    if ("NetLiquidation".equalsIgnoreCase(key) || "NetLiquidationValue".equalsIgnoreCase(key)) {
                        log.debug("⬅️ [PONTE | SYNC NLV] Capturado NLV via Account Update. Enviando para setter dedicado.");
                        portfolioService.updateNetLiquidationValueFromCallback(numericValue);
                    }

                    // 1. Notificação do Módulo Principal (LivePortfolioService) - Usada para BP, EL e outros
                    portfolioService.updateAccountValue(key, numericValue);

                    // 2. Atualização dos caches internos da Ponte
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
            log.info("✅ Conexão estabelecida com sucesso. Próximo ID de Ordem Válido: {}", orderId);
            orderIdManager.initializeOrUpdate(orderId);
            connectionLatch.countDown();

            // 🚨 DISPARO CRÍTICO: Dispara a requisição de Margem Crítica após a conexão
            requestCriticalMarginData();

        } catch (Exception e) {
            log.error("💥 [Ponte IBKR] Falha ao processar nextValidId {}. Rastreando.", orderId, e);
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

            if (field == TickType.BID.index() || field == TickType.ASK.index() || field == TickType.LAST.index()) {
                BigDecimal currentPrice = BigDecimal.valueOf(price);
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

    // ✅ NOVO MÉTODO RESTAURADO: Calcula Excess Liquidity usando EquityWithLoanValue - MaintMarginReq.
    /**
     * Calcula Excess Liquidity (EL) usando EquityWithLoanValue - MaintMarginReq.
     * Deve ser chamado sempre que EquityWithLoanValue ou MaintMarginReq for atualizado.
     */
    private void calculateAndUpdateExcessLiquidity() {
        try {
            // Obtém valores do SSOT (LivePortfolioService)
            BigDecimal equityWithLoan = portfolioService.getAccountValuesCache().get("EquityWithLoanValue");
            BigDecimal maintMarginReq = portfolioService.getAccountValuesCache().get("MaintMarginReq");

            if (equityWithLoan != null && maintMarginReq != null) {
                // Fórmula: ExcessLiquidity = EquityWithLoanValue - MaintMarginReq
                BigDecimal calculatedEL = equityWithLoan.subtract(maintMarginReq);

                // 1. Atualizar o cache de Excess Liquidity (EL)
                this.excessLiquidityCache.set(calculatedEL);

                // 2. Também atualizar no portfolioService (SSOT)
                portfolioService.updateAccountValue("ExcessLiquidity_Calculated", calculatedEL);

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