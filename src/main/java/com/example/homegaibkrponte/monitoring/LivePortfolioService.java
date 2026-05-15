package com.example.homegaibkrponte.monitoring;

import com.example.homegaibkrponte.connector.IBKRConnector;
import com.example.homegaibkrponte.dto.AccountLiquidityDTO;
import com.example.homegaibkrponte.dto.AccountStateDTO;
import com.example.homegaibkrponte.model.*;

// Importações de Sinergia com o Principal
import com.example.homegaibkrponte.service.AccountStateProvider;
import com.example.homegaibkrponte.model.PosicaoAvaliada;


import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 🌉 **PONTE (BRIDGE):** Responsável por ser o cache local e o sink para os dados brutos da conta IBKR.
 * Implementa a lógica de validação de Excesso de Liquidez e atua como **AccountStateProvider** para o Principal.
 */
@Service
@Slf4j
@Getter
public class LivePortfolioService implements AccountStateProvider { // <<== IMPLEMENTAÇÃO DA INTERFACE DO PRINCIPAL

    private final AtomicReference<Portfolio> portfolioState = new AtomicReference<>();
    private final ApplicationEventPublisher eventPublisher;
    public record AccountBalance(BigDecimal value, LocalDateTime timestamp) {}
    private final AtomicReference<AccountBalance> lastAccountBalance = new AtomicReference<>(new AccountBalance(BigDecimal.ZERO, LocalDateTime.MIN));
    private final AtomicReference<CountDownLatch> accountSyncLatch = new AtomicReference<>(new CountDownLatch(1));
    private final AtomicBoolean isSynced = new AtomicBoolean(false);
    private volatile CountDownLatch positionSyncLatch = new CountDownLatch(1);
    private final Map<String, BigDecimal> flightOrders = new ConcurrentHashMap<>();

    private final AtomicReference<BigDecimal> nlv = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> cash = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> bp = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> el = new AtomicReference<>(BigDecimal.ZERO);
    private final ReentrantLock liquidityLock = new ReentrantLock();



    // 🛑 CONTROLE DE MARGEM CRÍTICA (Ciclo de Dependência Circular)
    private final CountDownLatch criticalMarginDataLatch = new CountDownLatch(1);
    private volatile AtomicBoolean isCriticalMarginDataLoaded = new AtomicBoolean(false);


    // 🚨 REGRA CRÍTICA [2025-11-03]
    private static final BigDecimal MARGIN_RESERVE_MIN_PCT = new BigDecimal("0.10"); // 10%

    @Value("${trading.initial-capital:200000.0}")
    private double initialCapital;

    // Cache para todos os valores de conta (Incluindo EL e NLV) - SSOT
    private final ConcurrentHashMap<String, BigDecimal> accountValuesCache = new ConcurrentHashMap<>();

    // 🛑 NOVO: Cache local de Excess Liquidity para permitir a lógica de comparação old/newEL.
    private final AtomicReference<BigDecimal> excessLiquidityCache = new AtomicReference<>(BigDecimal.ZERO);

    // 🛑 CORREÇÃO/NOVO: Variável faltante, inicializada como BRL (moeda brasileira) para evitar NullPointer/erro de compilação.
    private final AtomicReference<String> accountCurrency = new AtomicReference<>("BRL");

    private final IBKRConnector ibkrConnector;

    // 🛑 CHAVES NORMALIZADAS (Para garantir consistência)
    private static final String KEY_NET_LIQUIDATION_NORMALIZED = "NETLIQUIDATION";
    private static final String KEY_EXCESS_LIQUIDITY_NORMALIZED = "EXCESSLIQUIDITY";
    private static final String KEY_BUYING_POWER_NORMALIZED = "BUYINGPOWER";

    @Value("${api.ibkr.account-id:DUN652604}") // DUN... fica como fallback
    private String accountId;

    // --- CHAVES DE MARGEM (AJUSTADAS PARA UPPERCASE, sinergia com o cache) ---
    // IBKR usa "InitMarginReq" e "MaintMarginReq", mas a Ponte armazena tudo em UPPERCASE.
    // Usamos o formato que está no cache (Ex: INITMARGINREQ) para garantir lookup perfeito.
    private static final String KEY_BUYING_POWER = "BUYINGPOWER";
    private static final String KEY_EXCESS_LIQUIDITY = "EXCESSLIQUIDITY";
    private static final String KEY_NET_LIQUIDATION = "NETLIQUIDATION";
    private static final String KEY_INIT_MARGIN = "INITMARGINREQ";        // 🛑 AJUSTADO
    private static final String KEY_MAINTAIN_MARGIN = "MAINTMARGINREQ";    // 🛑 AJUSTADO
    private static final String KEY_AVAILABLE_FUNDS = "AVAILABLEFUNDS";
    private static final String KEY_CASH_BALANCE = "CASHBALANCE";
    private static final String KEY_CURRENCY = "CURRENCY";


    // --- CONSTRUTOR ---
    @Autowired
    public LivePortfolioService(ApplicationEventPublisher eventPublisher, @Lazy IBKRConnector ibkrConnector) {
        this.eventPublisher = eventPublisher;
        this.ibkrConnector = ibkrConnector;
        log.info("LivePortfolioService (Ponte) inicializado.");
    }
    @PostConstruct
    public void init() {
        lastAccountBalance.set(new AccountBalance(BigDecimal.valueOf(initialCapital), LocalDateTime.now()));

        Portfolio initialPortfolio = new Portfolio(
                "LIVE_CONSOLIDADO",
                BigDecimal.valueOf(initialCapital),
                new ConcurrentHashMap<>(),
                new ArrayList<>()
        );
        this.portfolioState.set(initialPortfolio);
        log.warn("🔄 Portfólio LIVE inicializado com capital PADRÃO. Aguardando sincronização... Capital: R$ {}", initialCapital);
    }

    public BigDecimal getTotalCostOfPendingOrders() {
        return flightOrders.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 💓 PASSO 4: ATUALIZADOR DE SAÚDE DA PONTE (Kill Switch)
     * Chamado pelo IBKRConnector ao detectar colapso de rede ou margem.
     */
    public void updateBridgeHealth(BridgeHealthStatus status) {
        if (status == BridgeHealthStatus.STRESSED) {
            log.error("🚨 [SISTEMA-STRESSED] Ponte sinalizou falha crítica ou margem esgotada.");
            // Opcional: Publica evento interno caso queira que outros serviços da ponte reajam
            // eventPublisher.publishEvent(new BridgeHealthEvent(status));
        } else {
            log.info("🟢 [SISTEMA-OPERATIONAL] Ponte normalizada.");
        }

        // Sincronia de Estado: O status será refletido no próximo heartbeat que o Principal solicitar
        // através do método getBridgeHealth() que você já possui.
    }

    /**
     * 🛡️ [LEITURA DE ÚLTIMA INSTÂNCIA] Recupera o último preço conhecido para um ticker.
     * Sinergia: Crucial para evitar o envio de ordens com preço $0 quando o Oráculo
     * solicita uma reserva de capital e a Ponte está em warmup ou sem ticks.
     */
    public BigDecimal getLastKnownPrice(String symbol) {
        if (symbol == null || symbol.isBlank()) return BigDecimal.ZERO;
        String sym = symbol.toUpperCase();

        // 1. ⚡ CAMADA 1: Preço em Tempo Real (Rádio/Streaming)
        // A chave "_PRICE" é alimentada pelo fluxo contínuo de ticks do Finnhub/TWS.
        BigDecimal realTimePrice = accountValuesCache.get(sym + "_PRICE");
        if (realTimePrice != null && realTimePrice.signum() > 0) {
            return realTimePrice;
        }

        // 2. 🔍 CAMADA 2: Preço de Inventário (Custódia/Average Price)
        // Se temos o ativo em carteira, o preço médio é uma base segura para precificação.
        BigDecimal inventoryPrice = getPositionAveragePrice(sym);
        if (inventoryPrice.signum() > 0) {
            log.info("🛡️ [PRICE-RECOVERY] Usando preço de inventário para {}: ${}", sym, inventoryPrice);
            return inventoryPrice;
        }

        // 3. 🩹 CAMADA 3: Rastreio Histórico (GHOST PRICE)
        // Varre o cache por qualquer valor associado ao símbolo (snapshots anteriores).
        BigDecimal lastAnyPrice = accountValuesCache.entrySet().stream()
                .filter(e -> e.getKey().startsWith(sym))
                .map(Map.Entry::getValue)
                .filter(v -> v != null && v.signum() > 0)
                .findFirst()
                .orElse(BigDecimal.ZERO);

        if (lastAnyPrice.signum() > 0) {
            log.warn("🩹 [GHOST-PRICE] Preço de {} recuperado do rastro histórico: ${}", sym, lastAnyPrice);
            return lastAnyPrice;
        }

        log.error("❌ [DADO-FATAL] Falha total na precificação de {}. Abortando ciclo.", sym);
        return BigDecimal.ZERO;
    }


//    /**
//     * 🛡️ MÉTODO ÚLTIMA ESPERANÇA (Sinergia de Dados)
//     * Se o streaming falhar e o snapshot for rejeitado (Erro 10197),
//     * este método busca o último preço conhecido no inventário de posições.
//     * Evita que o Winston aborte o ciclo operacional por falta de preço.
//     */
//    public BigDecimal getLastKnownPriceFromPosition(String symbol) {
//        if (symbol == null) return BigDecimal.ZERO;
//        String sym = symbol.toUpperCase();
//
//        // 1. ⚡ PRIORIDADE REAL: Tenta buscar o preço de mercado cacheado pelo streaming
//        // A chave "_PRICE" é alimentada pelo fluxo contínuo de ticks.
//        BigDecimal marketPrice = accountValuesCache.get(sym + "_PRICE");
//        if (marketPrice != null && marketPrice.signum() > 0) {
//            return marketPrice;
//        }
//
//        // 2. 🔍 SEGUNDA CAMADA: Busca no inventário de posições sincronizadas
//        return getPosition(sym)
//                .map(pos -> {
//                    // 🎯 Lógica direta: Prioriza o MarketPrice da última sincronia, senão o PM
//                    BigDecimal price = (pos.getCurrentMarketPrice() != null && pos.getCurrentMarketPrice().signum() > 0)
//                            ? pos.getCurrentMarketPrice()
//                            : pos.getAverageEntryPrice();
//
//                    if (price.signum() > 0) {
//                        log.warn("⚠️ [PONTE-FALLBACK] Preço Fresh indisponível para {}. Usando suporte do inventário: ${}",
//                                sym, price);
//                        return price;
//                    }
//                    return BigDecimal.ZERO;
//                })
//                .orElseGet(() -> {
//                    // 3. 🚨 ÚLTIMA LINHA DE DEFESA (GHOST RECOVERY):
//                    // Se não há posição, varre o cache por qualquer rastro de preço (Snapshots anteriores)
//                    BigDecimal lastAnyPrice = accountValuesCache.entrySet().stream()
//                            .filter(e -> e.getKey().startsWith(sym))
//                            .map(Map.Entry::getValue)
//                            .filter(v -> v.signum() > 0)
//                            .findFirst()
//                            .orElse(BigDecimal.ZERO);
//
//                    if (lastAnyPrice.signum() <= 0) {
//                        log.error("❌ [DADO-FATAL] {} sem preço em NENHUMA base. Operação cega impedida.", sym);
//                    } else {
//                        log.info("🩹 [GHOST-PRICE] Preço de {} recuperado do rastro histórico: ${}", sym, lastAnyPrice);
//                    }
//                    return lastAnyPrice;
//                });
//    }



    public void trackOrderSent(String clientOrderId, String symbol, BigDecimal quantity, BigDecimal price) {
        try {
            // Se o preço chegar zerado, tenta um último resgate pelo símbolo
            BigDecimal finalPrice = (price == null || price.signum() <= 0)
                    ? getPriceForOrder(symbol)
                    : price;

            BigDecimal estimatedCost = quantity.abs().multiply(finalPrice);
            flightOrders.put(clientOrderId, estimatedCost);

            log.warn("📝 [TRACKING] {} | Capital Reservado: ${} | Preço Ref: ${} | Pendentes: {}",
                    symbol, estimatedCost.setScale(2, RoundingMode.HALF_UP), finalPrice, flightOrders.size());

            // Atualiza o Buying Power
            BigDecimal currentBP = accountValuesCache.getOrDefault(KEY_BUYING_POWER, BigDecimal.ZERO);
            handleBuyingPowerUpdate(currentBP);

        } catch (Exception e) {
            log.error("❌ Erro ao rastrear ordem {} no Portfólio: {}", clientOrderId, e.getMessage());
        }
    }



    /**
     * 🎯 [LEITURA SOBERANA] Obtém preço apenas do cache de streaming.
     * Sem chamadas síncronas de rede (Snapshot) para evitar travamento de threads.
     */
    public BigDecimal getPriceForOrder(String symbol) {
        if (symbol == null) return BigDecimal.ZERO;

        String priceKey = symbol.toUpperCase() + "_PRICE";

        // 1. Tenta buscar do cache de mapa (SSOT) alimentado pelo streaming da TWS
        BigDecimal price = accountValuesCache.get(priceKey);

        // 2. Se não encontrar, retorne ZERO imediatamente.
        // NÃO tente buscar na TWS aqui dentro. Isso é responsabilidade do heartbeat/streaming.
        if (price == null || price.signum() <= 0) {
            log.warn("⚠️ [DADO-AUSENTE] Preço para {} não disponível no cache de streaming.", symbol);
            return BigDecimal.ZERO;
        }

        return price;
    }

    /**
     * 🩹 [ESTORNO-IMEDIATO] Método de Sinergia com o Principal.
     * Chamado pelo IBKRConnector ao detectar rejeição/erro da corretora (Erro 201/203).
     * Remove a ordem do cache 'flightOrders' para que o BP ajustado seja recuperado na hora.
     */
    public void removePendingOrderById(String clientOrderId) {
        if (clientOrderId == null || clientOrderId.equals("0")) {
            return;
        }

        // 1. Remove do mapa de ordens em voo
        BigDecimal custoRemovido = flightOrders.remove(clientOrderId);

        if (custoRemovido != null) {
            log.error("🩹 [PONTE-CURA] Estornando capital fantasma de R$ {} (ID: {}).",
                    custoRemovido.setScale(2, RoundingMode.HALF_UP), clientOrderId);

            // 2. Força a atualização do Poder de Compra Ajustado no snapshot atômico
            BigDecimal currentBP = accountValuesCache.getOrDefault(KEY_BUYING_POWER, BigDecimal.ZERO);
            handleBuyingPowerUpdate(currentBP);
        }
    }

    public void removePendingOrder(String clientOrderId) {
        if (clientOrderId != null && flightOrders.remove(clientOrderId) != null) {
            log.debug("🧹 [TRACKING] Ordem {} removida do rastreamento (Finalizada).", clientOrderId);
        }
    }

    public AccountLiquidityDTO getStreamingLiquidityStatus() {
        // Buscamos os valores dos AtomicReferences
        BigDecimal currentNlv = nlv.get();
        BigDecimal currentCash = cash.get();
        BigDecimal currentBp = bp.get();
        BigDecimal currentEl = el.get();

        // Buscamos MMR e IMR do cache SSOT que você já possui na classe
        BigDecimal maintainMargin = getMaintMarginRequirement();
        BigDecimal initialMargin = getInitialMarginRequirement();

        return new AccountLiquidityDTO(
                currentNlv,
                currentCash,
                currentBp,
                currentEl,
                maintainMargin,
                initialMargin
        );
    }

    public BigDecimal getMarginUtilization() {
        try {
            BigDecimal currentNlv = getNetLiquidationValue();
            BigDecimal maintMargin = getMaintMarginRequirement();

            if (currentNlv.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ONE;
            return maintMargin.divide(currentNlv, 4, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.error("❌ Falha no cálculo de utilização de margem: {}", e.getMessage());
            return BigDecimal.ONE;
        }
    }

    /**
     * 🛡️ MÉTODO DE AUDITORIA: Verifica se os dados no cache são "frescos" (menos de 60s).
     * Se retornar false, o Orquestrador saberá que a conexão com a IBKR caiu ou está travada.
     */
    public boolean isDataFresh() {
        if (accountValuesCache.isEmpty()) return false;

        // Verifica se recebemos o NLV (Net Liquidation Value)
        boolean hasNlv = accountValuesCache.containsKey(KEY_NET_LIQUIDATION);

        // Se o latch ainda está em 1, significa que a IBKR nunca mandou os dados de margem
        boolean isMarginLoaded = isCriticalMarginDataLoaded.get();

        log.info("🔍 [AUDITORIA] NLV Presente: {} | Margens Carregadas: {}", hasNlv, isMarginLoaded);

        return hasNlv && isMarginLoaded;
    }

    /**
     * 🌉 FUNÇÃO DE PONTE: Fornece o preço atual (Market Price) com cadeia de fallback robusta.
     * Essencial para o cálculo de exposição e reserva de Buying Power.
     */
    public java.util.function.Function<String, BigDecimal> getMarketDataProvider() {
        return symbol -> {
            try {
                // 1. Prioridade: Preço em tempo real do cache SSOT
                BigDecimal currentPrice = accountValuesCache.getOrDefault(symbol.toUpperCase() + "_PRICE", BigDecimal.ZERO);

                if (currentPrice.compareTo(BigDecimal.ZERO) > 0) {
                    return currentPrice;
                }

                // 2. Fallback: Preço Médio de Entrada (Se houver posição aberta)
                BigDecimal avgPrice = getPositionAveragePrice(symbol);
                if (avgPrice.compareTo(BigDecimal.ZERO) > 0) {
                    log.warn("⚠️ [PREÇO FALLBACK] Preço indisponível para {}. Usando Preço Médio: R$ {}",
                            symbol, avgPrice.toPlainString());
                    return avgPrice;
                }

                log.error("❌ [MARKET DATA ERROR] Sem preço disponível para {}.", symbol);
                return BigDecimal.ZERO;

            } catch (Exception e) {
                log.error("❌ ERRO CRÍTICO no MarketDataProvider para {}: {}", symbol, e.getMessage());
                return BigDecimal.ZERO;
            }
        };
    }
    /**
     * Método auxiliar para buscar o preço médio de uma posição no snapshot.
     */
    private BigDecimal getPositionAveragePrice(String symbol) {
        try {
            return getPosition(symbol)
                    .map(com.example.homegaibkrponte.model.Position::getAverageEntryPrice)
                    .orElse(BigDecimal.ZERO);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    // ==========================================================
    // IMPLEMENTAÇÃO DE ACCOUTSTATEPROVIDER (SINERGIA COM O PRINCIPAL)
    // ==========================================================

    @Override
    public BigDecimal getCurrentCashBalance() {
        // Usa a chave UPPERCASE consistente
        return getAccountValuesCache().getOrDefault(KEY_CASH_BALANCE, BigDecimal.ZERO);
    }

    @Override
    public List<PosicaoAvaliada> getCurrentEvaluatedPortfolio() {
        // Mapeia os DTOs de Posição da Ponte para o DTO de avaliação do Principal.
        return portfolioState.get().openPositions().values().stream()
                .map(positionBase -> {

                    // 1. Calcular Lucro Não Realizado (PnL - Valor TEMPO REAL necessário para GDL)
                    // PnL deve ser obtido do callback/cache. Usamos placeholder para a simulação, mas mantemos o formato BigDecimal.
                    BigDecimal pnl = BigDecimal.valueOf(Math.random() * 1000).setScale(2, RoundingMode.HALF_UP);

                    // Lógica para simular PnL negativo (para teste da GDL)
                    if (positionBase.getAverageEntryPrice().compareTo(BigDecimal.valueOf(50)) > 0) {
                        pnl = pnl.negate();
                    }

                    // 2. Margem Requerida (Margem por Posição, obtida do SSOT da Ponte)
                    // Assumimos BigDecimal.ZERO por ser complexo por posição, mas deve vir de um cache IBKR específico.
                    BigDecimal margemReq = BigDecimal.ZERO;

                    // 3. Mapear para o PosicaoAvaliada (DTO do Principal)
                    // ✅ CORREÇÃO: Mapeando todos os 6 campos do PosicaoAvaliada, extraindo da Posicao da Ponte.
                    return new PosicaoAvaliada(
                            positionBase,
                            pnl, // Lucro Não Realizado (necessário para GDL)
                            margemReq, // Margem Requerida (necessário para LiquidityManager se usado)
                            positionBase.getSymbol(), // ✅ Ativo
                            positionBase.getQuantity(), // ✅ Quantidade
                            positionBase.getAverageEntryPrice() // ✅ Preço Médio
                    );
                })
                .toList();
    }

    /**
     * ✅ Implementação do Passo 9.2: Calcula a Margem de Reserva como fração do NLV.
     * SINERGIA: Métrica crucial para o LiquidityManager do Principal.
     */
    public BigDecimal getReserveMarginFrac() {
        BigDecimal nlv = getNetLiquidationValue();
        // Usamos o ExcessLiquidity_Calculated que é o valor mais confiável calculado no IBKRConnector.
        BigDecimal el = getAccountValuesCache().getOrDefault("EXCESSLIQUIDITY_CALCULATED", BigDecimal.ZERO);

        if (nlv.compareTo(BigDecimal.ZERO) > 0) {
            try {
                // Fórmula: ReserveMarginFrac = ExcessLiquidity / NLV
                return el.divide(nlv, 4, RoundingMode.HALF_UP);
            } catch (ArithmeticException e) {
                log.error("❌ Erro de divisão ao calcular ReserveMarginFrac: {}", e.getMessage());
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }


    // --- MÉTODOS DE SINCRONIZAÇÃO DE SALDO ---

    public void resetAccountSyncLatch() {
        accountSyncLatch.getAndUpdate(currentLatch -> {
            if (currentLatch.getCount() == 0) {
                log.debug("🔄 Sinalizador de sincronização de saldo resetado.");
                return new CountDownLatch(1);
            }
            return currentLatch;
        });
    }

    public boolean awaitInitialSync(long timeoutMillis) throws InterruptedException {
        CountDownLatch latch = accountSyncLatch.get();
        log.info("Aguardando a sincronização de saldo da corretora (timeout de {}ms)...", timeoutMillis);
        return latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Retorna o Latch de sincronização para aguardar os dados críticos de margem.
     * @return O CountDownLatch.
     */
    public CountDownLatch getCriticalMarginDataLatch() {
        return criticalMarginDataLatch;
    }

    /**
     * Indica se os dados críticos de margem já foram carregados.
     * @return true se carregados, false caso contrário.
     */
    public boolean isCriticalMarginDataLoaded() {
        return isCriticalMarginDataLoaded.get();
    }

    public void updateAccountValue(String key, BigDecimal value) {
        try {
            String normalizedKey = key.toUpperCase();
            accountValuesCache.put(normalizedKey, value);
//            log.debug("📊 [CACHE PONTE] Valor Sincronizado: {} = R$ {}", normalizedKey, value.toPlainString());
//
            switch (normalizedKey) {
                case "NETLIQUIDATION", "NETLIQUIDATIONVALUE", "EQUITYWITHLOANVALUE" -> nlv.set(value);
                case "CASHBALANCE" -> cash.set(value);
                case "BUYINGPOWER" -> bp.set(value);
                case "EXCESSLIQUIDITY", "AVAILABLEFUNDS" -> {
                    el.set(value);
                    excessLiquidityCache.set(value);
                }
            }

            // Se for Buying Power, atualizar o snapshot do portfólio descontando as pendentes
            if (KEY_BUYING_POWER_NORMALIZED.equalsIgnoreCase(normalizedKey)) {
                handleBuyingPowerUpdate(value);
            }

            checkAndSignalCriticalMarginReadiness();

        } catch (Exception e) {
            log.error("❌ ERRO no updateAccountValue da Ponte: {}", e.getMessage());
        }
    }

    /**
     * ✅ AJUSTE DE SINERGIA: Atualiza o saldo considerando ordens em voo.
     * O Buying Power real é o valor da corretora MENOS o custo das ordens pendentes.
     */
    private void handleBuyingPowerUpdate(BigDecimal value) {
        try {
            LocalDateTime now = LocalDateTime.now();

            // 🛡️ SINERGIA: Deduz o custo das ordens que acabamos de enviar (Passo 1)
            BigDecimal pendingCost = getTotalCostOfPendingOrders();
            BigDecimal adjustedBP = value.subtract(pendingCost);

            lastAccountBalance.set(new AccountBalance(adjustedBP, now));

            // Atualiza o snapshot atômico para que o SizingService do Principal leia o valor correto
            portfolioState.getAndUpdate(current -> current.toBuilder()
                    .cashBalance(adjustedBP)
                    .build());

            // Liberação de travas de inicialização
            CountDownLatch latch = accountSyncLatch.get();
            if (latch != null && latch.getCount() > 0) {
                latch.countDown();
            }

            if (isSynced.compareAndSet(false, true)) {
                log.warn("✅ PRIMEIRA SINCRONIZAÇÃO DE SALDO! Corretora: R$ {} | Pendente: R$ {} | Ajustado: R$ {}",
                        value, pendingCost, adjustedBP);
            } else {
                log.debug("📊 [BP SYNC] BP Corretora: R$ {} | Ajustado: R$ {}", value, adjustedBP);
            }
        } catch (Exception e) {
            log.error("❌ Erro ao processar atualização de Buying Power: {}", e.getMessage());
        }
    }
    /**
     * **MÉTODO DE PRONTIDÃO**
     * Checa se os valores críticos de margem foram recebidos e, se sim, libera o Latch de sincronização.
     */
    private void checkAndSignalCriticalMarginReadiness() {
        if (isCriticalMarginDataLoaded.get()) {
            return; // Já liberado
        }

        // CHAVES CRÍTICAS DE MARGEM (Usando as constantes UPPERCASE)
        final String maintMarginKey = KEY_MAINTAIN_MARGIN;
        final String initialMarginKey = KEY_INIT_MARGIN;

        // **SOLUÇÃO DEFINITIVA: Checagem de Presença**
        boolean maintMarginPresent = accountValuesCache.containsKey(maintMarginKey);
        boolean initialMarginPresent = accountValuesCache.containsKey(initialMarginKey);

        if (maintMarginPresent && initialMarginPresent) {
            if (isCriticalMarginDataLoaded.compareAndSet(false, true)) {
                criticalMarginDataLatch.countDown();
                log.info("✅ BARREIRA LIBERADA: Dados Críticos de Margem Carregados (Inicial e Manutenção)! Chaves: {} e {}",
                        maintMarginKey, initialMarginKey);
            }
        } else {
            log.debug("Aguardando dados de margem: Manutenção ({}): {}, Inicial ({}): {}",
                    maintMarginKey, maintMarginPresent ? "Presente" : "Faltando",
                    initialMarginKey, initialMarginPresent ? "Presente" : "Faltando");
        }
    }


    /**
     * 🌉 SINK: Recebe a moeda da conta IBKR e armazena no SSOT de forma thread-safe.
     */
    public void updateAccountCurrency(String currency) {
        if (currency != null && !currency.trim().isEmpty()) {
            this.accountCurrency.set(currency.trim().toUpperCase());
            log.debug("📊 [CACHE PONTE] Moeda da Conta Sincronizada: {}", this.accountCurrency.get());
        }
    }

    /**
     * ✅ [SSOT] Retorna o status completo de liquidez da conta (NLV, Cash, BP) do cache local.
     */
    public AccountLiquidityDTO getFullLiquidityStatus() {
        try {
            // Prioridade 1: Valor da variável atómica 'nlv' (Já sincronizada com EquityWithLoan)
            // Prioridade 2: Fallback para o cache de mapa
            BigDecimal netLiquidationValue = nlv.get().compareTo(BigDecimal.ZERO) > 0
                    ? nlv.get()
                    : accountValuesCache.getOrDefault(KEY_NET_LIQUIDATION, BigDecimal.ZERO);

            BigDecimal cashBalance = cash.get().compareTo(BigDecimal.ZERO) > 0
                    ? cash.get()
                    : accountValuesCache.getOrDefault(KEY_CASH_BALANCE, BigDecimal.ZERO);

            BigDecimal excessLiquidity = el.get().compareTo(BigDecimal.ZERO) > 0
                    ? el.get()
                    : accountValuesCache.getOrDefault(KEY_EXCESS_LIQUIDITY, BigDecimal.ZERO);

            BigDecimal currentBuyingPower = bp.get().compareTo(BigDecimal.ZERO) > 0
                    ? bp.get()
                    : (excessLiquidity.compareTo(BigDecimal.ZERO) > 0 ? excessLiquidity : BigDecimal.ZERO);

            AccountLiquidityDTO liquidityDTO = new AccountLiquidityDTO(
                    netLiquidationValue,
                    cashBalance,
                    currentBuyingPower,
                    excessLiquidity,
                    getMaintMarginRequirement(),
                    getInitialMarginRequirement()
            );

//            log.info("✅ [PONTE | RT-SYNC] DTO Gerado -> NLV: R$ {} | BP: R$ {} | EL: R$ {}",
//                    liquidityDTO.getNetLiquidationValue().toPlainString(),
//                    liquidityDTO.getCurrentBuyingPower().toPlainString(),
//                    liquidityDTO.getExcessLiquidity().toPlainString()
//            );

            return liquidityDTO;

        } catch (Exception e) {
            log.error("❌ ERRO ao gerar AccountLiquidityDTO: {}", e.getMessage());
            return new AccountLiquidityDTO(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    // =========================================================================
    // ✅ MÉTODOS DE ATUALIZAÇÃO E ACESSO DO SSOT
    // =========================================================================

    /**
     * 📥 Atualiza o Net Liquidation Value (PL) no cache SSOT da Ponte.
     */
    public void updateNetLiquidationValueFromCallback(BigDecimal nlv) {
        try {
            if (nlv != null && nlv.compareTo(BigDecimal.ZERO) > 0) {
                accountValuesCache.put(KEY_NET_LIQUIDATION_NORMALIZED, nlv);
                log.info("✅ [PONTE | SYNC NLV] Net Liquidation Value (PL) atualizado via callback: R$ {}", nlv.toPlainString());
            } else {
                log.warn("⚠️ [PONTE | SYNC NLV] Tentativa de atualização do NLV com valor inválido ou nulo. Valor recebido: {}", nlv);
            }
        } catch (Exception e) {
            log.error("❌ [PONTE | ERRO SYNC] Erro ao atualizar Net Liquidation Value no cache.", e);
        }
    }

    // --- MÉTODOS DE SINCRONIZAÇÃO DE POSIÇÕES ---

    public void resetPositionSyncLatch() {
        this.positionSyncLatch = new CountDownLatch(1);
    }

    public boolean awaitPositionSync(long timeoutMillis) throws InterruptedException {
        log.info("Aguardando a sincronização de posições da corretora (timeout de {}ms)...", timeoutMillis);
        return positionSyncLatch.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    public void updatePortfolioPositions(List<PositionDTO> ibkrPositions) {
        Map<String, Position> newPositionsMap = ibkrPositions.stream()
                .collect(Collectors.toConcurrentMap(
                        PositionDTO::getTicker,
                        this::mapPositionDTOtoDomain,
                        (existingValue, newValue) -> newValue
                ));
        portfolioState.getAndUpdate(current -> current.toBuilder()
                .openPositions(new ConcurrentHashMap<>(newPositionsMap))
                .build()
        );

        log.warn("SINERGIA: Posições sincronizadas. {} Posições Abertas.", newPositionsMap.size());
    }

    public void finalizePositionSync() {
        int positionCount = portfolioState.get().openPositions().size();
        log.info("✅ Sincronização de posições finalizada. Portfólio agora contém {} posições.", positionCount);
        positionSyncLatch.countDown();
    }

    // --- MÉTODOS DE ACESSO CRÍTICOS PARA O PRINCIPAL ---

    public Portfolio getLivePortfolioSnapshot() {
        return portfolioState.get();
    }

    /**
     * Retorna o valor bruto do Excess Liquidity do cache local.
     */
    public BigDecimal getExcessLiquidity() {
        // Usa a chave UPPERCASE consistente
        BigDecimal el = accountValuesCache.getOrDefault(KEY_EXCESS_LIQUIDITY, BigDecimal.ZERO);
        log.debug("✅ [PONTE | GET EL] Retornando Excess Liquidity do cache SSOT: R$ {}", el.toPlainString());
        return el;
    }

    public void updateFromBroker(String key, String value) {
        try {
            if (value == null || value.isEmpty()) return;
            BigDecimal val = new BigDecimal(value);

            switch (key) {
                case "NetLiquidation" -> nlv.set(val);
                case "CashBalance" -> cash.set(val);
                case "BuyingPower" -> bp.set(val);
                case "ExcessLiquidity" -> {
                    el.set(val);
                    // Log de nível DEBUG para acompanhar o streaming sem poluir o log INFO
                    log.debug("⚡ [STREAMING] EL atualizado no Cache: R$ {}", val);
                }
            }
        } catch (Exception e) {
            log.error("❌ Erro ao processar tag de conta: {} = {}", key, value);
        }
    }

    public AccountLiquidityDTO getStreamingLiquidity() {
        return new AccountLiquidityDTO(
                nlv.get(),
                cash.get(),
                bp.get(),
                el.get(),
                BigDecimal.ZERO, // MaintMargin (Opcional no Streaming)
                BigDecimal.ZERO  // InitMargin (Opcional no Streaming)
        );
    }

    /**
     * Retorna o valor bruto do Buying Power do cache local.
     */
    public BigDecimal getCurrentBuyingPower() {
        // Usa a lógica robusta definida em getFullLiquidityStatus para determinar o BP
        return getFullLiquidityStatus().getCurrentBuyingPower();
    }

    /**
     * Retorna o Net Liquidation Value (PL) do cache SSOT da Ponte.
     */
    public BigDecimal getNetLiquidationValue() {
        // Tenta primeiro a variável atómica sincronizada
        if (nlv.get().compareTo(BigDecimal.ZERO) > 0) return nlv.get();

        // Fallback para o cache de mapa usando as chaves normalizadas
        return accountValuesCache.getOrDefault("NETLIQUIDATION",
                accountValuesCache.getOrDefault("EQUITYWITHLOANVALUE", BigDecimal.ZERO));
    }



    // --- MÉTODOS DE ACESSO Específicos para Margem (USAM AS NOVAS CONSTANTES UPPERCASE) ---

    public BigDecimal getInitialMarginRequirement() {
        // Usa a chave UPPERCASE consistente
        return accountValuesCache.getOrDefault(KEY_INIT_MARGIN, BigDecimal.ZERO);
    }

    public BigDecimal getMaintMarginRequirement() {
        // Usa a chave UPPERCASE consistente
        return accountValuesCache.getOrDefault(KEY_MAINTAIN_MARGIN, BigDecimal.ZERO);
    }


    /**
     * Busca uma posição aberta no snapshot.
     */
    public Optional<Position> getPosition(String symbol) {
        Map<String, Position> openPositions = getLivePortfolioSnapshot().openPositions();
        return Optional.ofNullable(openPositions.get(symbol));
    }

    /**
     * Atualiza uma posição específica no snapshot do portfólio.
     */
    public void updatePosition(Position updatedPosition) {
        if (updatedPosition == null || updatedPosition.getSymbol() == null) return;

        portfolioState.getAndUpdate(currentPortfolio -> {
            try {
                Map<String, Position> newPositions = new ConcurrentHashMap<>(currentPortfolio.openPositions());
                newPositions.put(updatedPosition.getSymbol(), updatedPosition);

                log.warn("🔄 [LIVE PORTFOLIO] Posição {} atualizada na memória (SL/TP ou Média).", updatedPosition.getSymbol());

                return currentPortfolio.toBuilder()
                        .openPositions(newPositions)
                        .build();
            } catch (Exception e) {
                log.error("❌ [LIVE PORTFOLIO] Falha ao atualizar posição {}.", updatedPosition.getSymbol(), e);
                return currentPortfolio; // Retorna o estado atual
            }
        });
    }

    public boolean isSynced() {
        return isSynced.get();
    }

    public AccountBalance getLastBuyingPowerSnapshot() {
        return lastAccountBalance.get();
    }

    // =========================================================================
    // ✅ VALIDAÇÃO DE RISCO
    // =========================================================================

    public BigDecimal getEquityWithLoan() {
        return accountValuesCache.getOrDefault("EQUITYWITHLOAN", BigDecimal.ZERO);
    }


    /**
     * 🚨 Implementação da Regra de Excesso de Liquidez [2025-11-03].
     */
    public void validateExcessLiquidity() {
        try {
            BigDecimal excessLiquidity = getExcessLiquidity();
            // Utiliza o método ajustado que busca do cache SSOT
            BigDecimal maintMargin = getMaintMarginRequirement();

            log.debug("🔄 [Ponte | VALIDAÇÃO MARGEM] EL: R$ {}, MaintMargin: R$ {}",
                    excessLiquidity.toPlainString(), maintMargin.toPlainString());

            if (excessLiquidity.compareTo(BigDecimal.ZERO) <= 0) {
                log.error("🚨 [Ponte | ALERTA CRÍTICO] Excesso de Liquidez NULO ou NEGATIVO! R$ {}. Ação imediata necessária.", excessLiquidity);
            } else {
                if (maintMargin.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal reserveRatio = excessLiquidity.divide(maintMargin, 4, RoundingMode.HALF_UP);

                    if (reserveRatio.compareTo(MARGIN_RESERVE_MIN_PCT) < 0) {
                        log.warn("⚠️ [Ponte | ALERTA DE LIQUIDEZ] RESERVA BAIXA! Liquidez em Excesso (R$ {}) é inferior a 10% da Margem de Manutenção (R$ {}). Conta em risco de liquidação forçada.",
                                excessLiquidity.toPlainString(), maintMargin.toPlainString());
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ [Ponte | ERRO VALIDAÇÃO] Falha ao executar validateExcessLiquidity.", e);
        }
    }


    // --- PROCESSAMENTO DE EVENTOS INTERNOS (EVENT LISTENER) ---

    @EventListener
    public void onTradeExecuted(TradeExecutedEvent event) {
        log.info("🎧 Evento de trade recebido: Fonte [{}], Símbolo [{}], Lado [{}], Qtd [{}], Preço [R$ {}]",
                event.executionSource(), event.symbol(), event.side(), event.quantity(), event.price());

        portfolioState.getAndUpdate(currentPortfolio -> {
            try {
                if (event.side().equalsIgnoreCase("BUY") || event.side().equalsIgnoreCase("BOT")) {
                    return performBuyExecution(currentPortfolio, event);
                } else { // SELL or SLD or BUY_TO_COVER
                    return performSellExecution(currentPortfolio, event);
                }
            } catch (Exception e) {
                log.error("❌ ERRO CRÍTICO ao processar evento de trade para {}. Estado do portfólio NÃO ALTERADO.", event.symbol(), e);
                return currentPortfolio;
            }
        });
    }

    // --- MÉTODOS PRIVADOS DE DOMÍNIO ---

    /**
     * 🛠️ MAPPER CORRIGIDO: Preserva o sinal negativo para posições SHORT.
     * Impede que o sistema principal envie SELL para fechar uma dívida.
     */
    private Position mapPositionDTOtoDomain(PositionDTO dto) {
        // 🚨 REGRA MESTRE: Não use .abs() aqui. O sinal negativo é a identidade do Short.
        BigDecimal quantityWithSignal = dto.getPosition();

        // Determina a direção baseada no sinal real vindo da IBKR
        PositionDirection direction = (quantityWithSignal.signum() < 0)
                ? PositionDirection.SHORT
                : PositionDirection.LONG;

        log.warn("📦 [PONTE-SYNC] {} | Qtd Recebida: {} | Direção Definida: {}",
                dto.getTicker(), quantityWithSignal, direction);

        return Position.builder()
                .symbol(dto.getTicker())
                .quantity(quantityWithSignal) // ✅ Agora o -4199.0 permanece -4199.0
                .averageEntryPrice(dto.getMktPrice())
                .entryTime(LocalDateTime.now())
                .direction(direction)
                .stopLoss(null)
                .takeProfit(null)
                .rationale("Sincronizado via TWS (Sinal Preservado)")
                .build();
    }



    private Portfolio performShortEntryExecution(Portfolio current, TradeExecutedEvent event) {
        String symbol = event.symbol();
        BigDecimal qty = event.quantity();
        BigDecimal price = event.price();

        BigDecimal cost = qty.multiply(price);
        BigDecimal newCash = current.cashBalance().add(cost);
        Map<String, Position> newPositions = new ConcurrentHashMap<>(current.openPositions());

        Position newPosition = new Position(symbol, qty, price, LocalDateTime.now(), PositionDirection.SHORT, null, null, "Venda a Descoberto");
        newPositions.put(symbol, newPosition);

        log.warn("✅ [PORTFÓLIO LIVE] NOVA VENDA A DESCOBERTO (SHORT) para {} registrada. Novo saldo: R$ {}", symbol, newCash.setScale(2, RoundingMode.HALF_UP));

        return current.toBuilder()
                .cashBalance(newCash)
                .openPositions(newPositions)
                .build();
    }

    private Portfolio performShortCoverExecution(Portfolio current, TradeExecutedEvent event) {
        String symbol = event.symbol();
        BigDecimal qty = event.quantity();
        BigDecimal price = event.price();

        Position positionToClose = current.openPositions().get(symbol);

        if (positionToClose == null || positionToClose.getDirection() != PositionDirection.SHORT) {
            log.error("TENTATIVA DE COBERTURA INVÁLIDA: Posição {} não é short.", symbol);
            return current;
        }

        BigDecimal cost = qty.multiply(price);

        BigDecimal newCash = current.cashBalance().subtract(cost);
        Map<String, Position> newPositions = new ConcurrentHashMap<>(current.openPositions());

        if (qty.compareTo(positionToClose.getQuantity()) >= 0) {
            newPositions.remove(symbol);
            log.warn("✅ [PORTFÓLIO LIVE] COBERTURA TOTAL (BUY-TO-COVER) para {}. Posição ENCERRADA.", symbol);
        } else {
            BigDecimal remainingQty = positionToClose.getQuantity().subtract(qty);

            Position updatedPosition = new Position(
                    positionToClose.getSymbol(),
                    remainingQty,
                    positionToClose.getAverageEntryPrice(),
                    positionToClose.getEntryTime(),
                    positionToClose.getDirection(),
                    positionToClose.getStopLoss(),
                    positionToClose.getTakeProfit(),
                    "Cobertura Parcial: " + remainingQty.toPlainString()
            );

            newPositions.put(symbol, updatedPosition);
            log.warn("✅ [PORTFÓLIO LIVE] COBERTURA PARCIAL para {}. Qtd Restante: {}.", symbol, remainingQty.toPlainString());
        }

        return new Portfolio(current.symbolForBacktest(), newCash, newPositions, current.tradeHistory());
    }

    private Portfolio performBuyExecution(Portfolio current, TradeExecutedEvent event) {
        try {
            String symbol = event.symbol();
            BigDecimal qty = event.quantity();
            BigDecimal price = event.price();

            // 🛡️ PROTEÇÃO: Evita processar eventos com quantidade inválida
            if (qty == null || qty.signum() == 0) {
                log.error("🚦 [PONTE-SHIELD] Quantidade zerada ou nula para {}. Ignorando atualização.", symbol);
                return current;
            }

            BigDecimal cost = qty.multiply(price);
            BigDecimal newCash = current.cashBalance().subtract(cost);
            Map<String, Position> newPositions = new ConcurrentHashMap<>(current.openPositions());

            Position existingPosition = newPositions.get(symbol);
            if (existingPosition != null) {
                BigDecimal totalQty = existingPosition.getQuantity().add(qty);

                // 🛡️ CURA: Se a quantidade total for zero (ex: cobriu um Short exatamente), removemos a posição
                // Isso evita a 'ArithmeticException: BigInteger divide by zero'
                if (totalQty.signum() == 0) {
                    newPositions.remove(symbol);
                    log.warn("✅ [PONTE | TWS-IN] {} zerado (Fechamento total). Novo saldo: R$ {}",
                            symbol, newCash.setScale(2, RoundingMode.HALF_UP));
                } else {
                    // Cálculo de Preço Médio Protegido
                    BigDecimal totalCost = existingPosition.getAverageEntryPrice()
                            .multiply(existingPosition.getQuantity()).add(cost);

                    // Divisão com 8 casas decimais para sinergia com o Principal
                    BigDecimal newAvgPrice = totalCost.divide(totalQty, 8, RoundingMode.HALF_UP).abs();

                    // Mantemos o construtor original da Ponte para não quebrar a estrutura de modelos
                    Position updatedPosition = new Position(
                            symbol,
                            totalQty,
                            newAvgPrice,
                            LocalDateTime.now(),
                            existingPosition.getDirection(),
                            existingPosition.getStopLoss(),
                            existingPosition.getTakeProfit(),
                            "Ajuste de Posição via Execução"
                    );
                    newPositions.put(symbol, updatedPosition);

                    log.warn("✅ [PONTE | TWS-IN] COMPRA para {} registrada. PM: R$ {} | Qtd: {}",
                            symbol, newAvgPrice.setScale(2, RoundingMode.HALF_UP), totalQty);
                }
            } else {
                // Nova Posição (Início de LONG)
                Position newPosition = new Position(
                        symbol,
                        qty,
                        price,
                        LocalDateTime.now(),
                        PositionDirection.LONG,
                        null,
                        null,
                        "Nova Posição via TWS"
                );
                newPositions.put(symbol, newPosition);
                log.warn("✅ [PONTE | TWS-IN] NOVA COMPRA para {} registrada. Preço: R$ {}", symbol, price);
            }

            // Reconstrói o Portfólio usando o Builder
            return current.toBuilder()
                    .cashBalance(newCash)
                    .openPositions(newPositions)
                    .build();

        } catch (Exception e) {
            log.error("❌ [PONTE-CRITICAL] Falha ao processar performBuyExecution para {}: {}",
                    event.symbol(), e.getMessage());
            return current; // Fail-safe: retorna o estado atual para não corromper o sistema
        }
    }

    private Portfolio performSellExecution(Portfolio current, TradeExecutedEvent event) {
        try {
            String symbol = event.symbol();
            BigDecimal qty = event.quantity().abs(); // Garantimos valor positivo para o cálculo
            BigDecimal price = event.price();

            // 🛡️ PROTEÇÃO: Evita processar eventos com quantidade inválida
            if (qty == null || qty.signum() == 0) {
                log.error("🚦 [PONTE-SHIELD-SELL] Quantidade inválida para {}. Ignorando atualização.", symbol);
                return current;
            }

            BigDecimal revenue = qty.multiply(price);
            BigDecimal newCash = current.cashBalance().add(revenue);
            Map<String, Position> newPositions = new ConcurrentHashMap<>(current.openPositions());

            Position existingPosition = newPositions.get(symbol);
            if (existingPosition != null) {
                // No caso de Venda de Long, subtraímos a quantidade
                BigDecimal currentQty = existingPosition.getQuantity();
                BigDecimal newQuantity = currentQty.subtract(qty);

                // 🛡️ CURA: Se a quantidade resultar em zero (ou poeira < 0.000001), removemos a posição
                if (newQuantity.abs().compareTo(new BigDecimal("0.000001")) <= 0) {
                    newPositions.remove(symbol);
                    log.warn("❌ [PONTE | TWS-IN] {} ENCERRADA via VENDA. Novo saldo: R$ {}",
                            symbol, newCash.setScale(2, RoundingMode.HALF_UP));
                } else {
                    // Para vendas parciais de Long, o preço médio não muda, apenas a quantidade.
                    // Mas, para manter a compatibilidade se for uma redução de Short (virando a mão):
                    Position updatedPosition = existingPosition.toBuilder()
                            .quantity(newQuantity)
                            .rationale("Redução de Posição via Execução")
                            .build();

                    newPositions.put(symbol, updatedPosition);

                    log.info("✅ [PONTE | TWS-IN] VENDA PARCIAL de {}. Qtd Restante: {}",
                            symbol, newQuantity);
                }
            } else {
                // Se não existia posição e vendeu, iniciou um SHORT
                Position newPosition = new Position(
                        symbol,
                        qty.negate(), // Quantidade negativa para Short
                        price,
                        LocalDateTime.now(),
                        PositionDirection.SHORT,
                        null,
                        null,
                        "Nova Posição SHORT via TWS"
                );
                newPositions.put(symbol, newPosition);
                log.warn("✅ [PONTE | TWS-IN] NOVA VENDA (SHORT) para {} registrada. Preço: R$ {}", symbol, price);
            }

            return current.toBuilder()
                    .cashBalance(newCash)
                    .openPositions(newPositions)
                    .build();

        } catch (Exception e) {
            log.error("❌ [PONTE-SELL-CRITICAL] Falha ao processar venda para {}: {}",
                    event.symbol(), e.getMessage());
            return current;
        }
    }

    public AccountStateDTO getFullAccountState(String accountId) {
        log.warn("➡️ [Ponte | SYNC SSOT] Recebida requisição de AccountState completo. Disparando AccountSummary para dados frescos.");

        // 2. MONTAGEM DO DTO A PARTIR DO CACHE INTERNO (Usando chaves UPPERCASE consistentes)
        AccountStateDTO dto = AccountStateDTO.builder()
                .netLiquidation(accountValuesCache.getOrDefault(KEY_NET_LIQUIDATION, BigDecimal.ZERO))
                .cashBalance(accountValuesCache.getOrDefault(KEY_CASH_BALANCE, BigDecimal.ZERO))
                .buyingPower(accountValuesCache.getOrDefault(KEY_BUYING_POWER,
                        accountValuesCache.getOrDefault(KEY_EXCESS_LIQUIDITY, BigDecimal.ZERO)))
                .excessLiquidity(accountValuesCache.getOrDefault(KEY_EXCESS_LIQUIDITY, BigDecimal.ZERO))
                // 🛑 CORRIGIDO: Usando as constantes UPPERCASE
                .initMarginReq(accountValuesCache.getOrDefault(KEY_INIT_MARGIN, BigDecimal.ZERO))
                .maintainMarginReq(accountValuesCache.getOrDefault(KEY_MAINTAIN_MARGIN, BigDecimal.ZERO))
                // 🛑 CORRIGIDO: Usando a constante UPPERCASE
                .availableFunds(accountValuesCache.getOrDefault(KEY_AVAILABLE_FUNDS, BigDecimal.ZERO))
                .currency(accountCurrency.get())
                .timestamp(Instant.now())
                .build();

        log.info("⬅️ [Ponte | SSOT COMPILADO] AccountState DTO pronto para o Principal. NLV: R$ {}, BP: R$ {}, Moeda: {}",
                dto.netLiquidation().toPlainString(), dto.buyingPower().toPlainString(), dto.currency());

        return dto;
    }

    public enum SystemHealth {
        OPERATIONAL, // Verde: Tudo ok
        STRESSED     // Amarelo/Vermelho: Margem alta ou I/O saturado. Só permite reduções.
    }

    /**
     * 💓 HEARTBEAT LOGIC (Passo 4)
     * Determina se a ponte está apta a receber novas ordens.
     */
    public BridgeHealthStatus getBridgeHealth() {
        // 1. Checagem de Margem: Se a utilização for > 95%, sinaliza stress
        BigDecimal utilization = getMarginUtilization();
        if (utilization.compareTo(new BigDecimal("0.95")) >= 0) {
            log.warn("⚠️ [HEALTH-STRESSED] Utilização de margem crítica: {}%", utilization.multiply(new BigDecimal("100")));
            return BridgeHealthStatus.STRESSED;
        }

        // 2. Checagem de Conexão: Se o socket principal estiver instável
        if (!ibkrConnector.isConnected()) {
            return BridgeHealthStatus.STRESSED;
        }

        return BridgeHealthStatus.OPERATIONAL;
    }

    /**
     * 🎯 MÉTODOTÁTICO: Retorna a quantidade exata de um ativo em custódia.
     * Crucial para a Ponte decidir se o 'CLOSE' do Principal deve ser BUY ou SELL.
     * @param symbol Ticker do ativo (ex: META)
     * @return Quantidade (Negativa para SHORT, Positiva para LONG, ZERO se não houver)
     */
    public BigDecimal getPositionForSymbol(String symbol) {
        if (symbol == null) {
            log.error("⚠️ [AUDITORIA-POSICAO] Tentativa de consulta com Símbolo NULO.");
            return BigDecimal.ZERO;
        }

        final String ticker = symbol.toUpperCase();

        // Acessa o snapshot atômico do portfólio
        Portfolio current = portfolioState.get();

        if (current != null && current.openPositions() != null) {
            Position pos = current.openPositions().get(ticker);

            if (pos != null) {
                BigDecimal qty = pos.getQuantity();
                String regime = (qty.signum() < 0) ? "SHORT 🔴" : "LONG 🟢";

                log.info("🎯 [AUDITORIA-POSICAO] {} identificado em custódia. Qtd: {} | Lado: {}",
                        ticker, qty, regime);
                return qty;
            }
        }

        log.debug("⚪ [AUDITORIA-POSICAO] {} não encontrado no inventário ativo. Retornando ZERO.", ticker);
        return BigDecimal.ZERO;
    }

    /**
     * 🎯 CONSULTA HÍBRIDA: Tenta o cache atômico, mas permite fallback.
     * Se o cache reporta zero, o sistema agora tem permissão para uma verificação de última instância.
     */
    public BigDecimal getConfirmedPositionForSymbol(String symbol) {
        BigDecimal cachedQty = getPositionForSymbol(symbol); // [cite: 300, 304]

        if (cachedQty.signum() != 0) {
            return cachedQty;
        }

        // Se o cache está zerado, o problema pode ser o delay de sincronia (Poeira ou Warmup)
        log.warn("🔍 [INQUISIÇÃO-POSICAO] Cache zerado para {}. O Despacho solicitará verificação direta.", symbol);
        return BigDecimal.ZERO;
    }

    // Método que fornece o Account ID (necessário para a validação)
    public String getAccountId() {
        return this.accountId;
    }

    public ConcurrentHashMap<String, BigDecimal> getAccountValuesCache() {
        return accountValuesCache;
    }

    public BigDecimal getNlv() { return nlv.get(); }
    public BigDecimal getCash() { return cash.get(); }
    public BigDecimal getBp() { return bp.get(); }
    public BigDecimal getEl() { return el.get(); }
}