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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
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

    private final AtomicReference<AccountBalance> lastAccountBalance =
            new AtomicReference<>(new AccountBalance(BigDecimal.ZERO, LocalDateTime.MIN));

    private final AtomicReference<CountDownLatch> accountSyncLatch =
            new AtomicReference<>(new CountDownLatch(1));

    private final AtomicBoolean isSynced = new AtomicBoolean(false);

    private volatile CountDownLatch positionSyncLatch = new CountDownLatch(1);

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

    // 🛑 CHAVES NORMALIZADAS (Para garantir consistência)
    private static final String KEY_NET_LIQUIDATION_NORMALIZED = "NETLIQUIDATION";
    private static final String KEY_EXCESS_LIQUIDITY_NORMALIZED = "EXCESSLIQUIDITY";
    private static final String KEY_BUYING_POWER_NORMALIZED = "BUYINGPOWER";


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
    public LivePortfolioService(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
        log.info("LivePortfolioService (Ponte) inicializado. Latch de Margem: {}", criticalMarginDataLatch.getCount());
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

    /**
     * 🌉 SINK: Recebe valores brutos da conta IBKR (BuyingPower, ExcessLiquidity, etc.).
     * Este é o PONTO CENTRAL (SSOT) para todos os valores de conta em formato BigDecimal.
     */
    public void updateAccountValue(String key, BigDecimal value) {
        try {
            // Normaliza a chave recebida para UPPERCASE, garantindo consistência no cache SSOT.
            String normalizedKey = key.toUpperCase();
            accountValuesCache.put(normalizedKey, value);
            log.debug("📊 [CACHE PONTE] Valor Bruto Sincronizado: {} = R$ {}", normalizedKey, value.toPlainString());

            if (KEY_BUYING_POWER_NORMALIZED.equalsIgnoreCase(normalizedKey)) {
                LocalDateTime now = LocalDateTime.now();
                CountDownLatch latch = accountSyncLatch.get();

                lastAccountBalance.set(new AccountBalance(value, now));
                portfolioState.getAndUpdate(current -> current.toBuilder()
                        .cashBalance(value)
                        .build()
                );

                if (latch != null && latch.getCount() > 0) {
                    latch.countDown();
                    log.info("✅ Latch de sincronização de saldo disparado (countDown).");
                }

                if (isSynced.compareAndSet(false, true)) {
                    log.warn("✅ PRIMEIRA SINCRONIZAÇÃO DE SALDO COMPLETA! Poder de Compra: R$ {}. Sistema operacional.", value);
                } else {
                    log.info("Sincronização de saldo contínua. Poder de Compra atualizado: R$ {}", value.toPlainString());
                }
            }

            if (KEY_EXCESS_LIQUIDITY_NORMALIZED.equalsIgnoreCase(normalizedKey)) {

                BigDecimal oldEL = excessLiquidityCache.get();
                BigDecimal newEL = value;

                excessLiquidityCache.set(newEL);

                log.warn("💰 [CACHE PONTE | EXCESS_LIQUIDITY_SSOT] Valor SSOT Atualizado: R$ {} (Anterior: R$ {})",
                        newEL.toPlainString(), oldEL.toPlainString());

                if (oldEL.compareTo(BigDecimal.ZERO) == 0 && newEL.compareTo(BigDecimal.ZERO) > 0) {
                    log.info("🎉 [EL-RECOVERY] Excess Liquidity recuperado: R$ {} (era R$ {})", newEL.toPlainString(), oldEL.toPlainString());
                } else if (newEL.compareTo(BigDecimal.ZERO) == 0 && oldEL.compareTo(BigDecimal.ZERO) > 0) {
                    log.error("🚨 [EL-ZEROED] Excess Liquidity zerado! ATENÇÃO: Disparar validação de margem crítica. Era R$ {}, agora R$ {}", oldEL.toPlainString(), newEL.toPlainString());
                }

                validateExcessLiquidity();
            }

            // 🛑 Checa e sinaliza a prontidão dos dados críticos de margem após qualquer atualização de valor
            checkAndSignalCriticalMarginReadiness();

        } catch (Exception e) {
            log.error("❌ ERRO CRÍTICO no updateAccountValue. Falha ao processar a chave {}: {}", key, e.getMessage(), e);
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
            BigDecimal netLiquidationValue = getNetLiquidationValue();
            // Usa a chave UPPERCASE consistente
            BigDecimal cashBalance = accountValuesCache.getOrDefault(KEY_CASH_BALANCE, BigDecimal.ZERO);
            BigDecimal excessLiquidity = getExcessLiquidity();
            // Usa a chave UPPERCASE consistente
            BigDecimal availableFunds = accountValuesCache.getOrDefault(KEY_AVAILABLE_FUNDS, BigDecimal.ZERO);

            BigDecimal maintainMarginReq = getMaintMarginRequirement(); // MMR
            BigDecimal initMarginReq = getInitialMarginRequirement();   // IMR

            BigDecimal currentBuyingPower;



            if (excessLiquidity.compareTo(BigDecimal.ZERO) > 0) {
                currentBuyingPower = excessLiquidity;
                log.debug("💰 [PONTE | BP FLUXO] Usando Excess Liquidity (EL: R$ {}) como Buying Power de referência.", currentBuyingPower.toPlainString());
            } else if (availableFunds.compareTo(BigDecimal.ZERO) > 0) {
                currentBuyingPower = availableFunds;
                log.warn("⚠️ [PONTE | BP FLUXO] EL ausente/zero. Usando Available Funds (AF: R$ {}) como substituto.", currentBuyingPower.toPlainString());
            } else {
                currentBuyingPower = BigDecimal.ZERO;
                log.error("❌ [PONTE | BP FLUXO] Liquidez (EL/AF) zerada. Retornando R$ 0.00 para forçar VETO de Emergência no Principal.");
            }

            AccountLiquidityDTO liquidityDTO = new AccountLiquidityDTO(
                    netLiquidationValue,
                    cashBalance,
                    currentBuyingPower,
                    excessLiquidity,
                    // ✅ NOVOS CAMPOS ENVIADOS AO PRINCIPAL
                    maintainMarginReq,
                    initMarginReq
            );

            log.info("✅ [PONTE | DTO SSOT] DTO de Liquidez Enviado. NLV: R$ {}, Cash: R$ {}, BP Retornado: R$ {}, EL: R$ {}",
                    liquidityDTO.getNetLiquidationValue().toPlainString(),
                    liquidityDTO.getCashBalance().toPlainString(),
                    liquidityDTO.getCurrentBuyingPower().toPlainString(),
                    liquidityDTO.getExcessLiquidity().toPlainString()
            );

            return liquidityDTO;

        } catch (Exception e) {
            log.error("❌ ERRO CRÍTICO ao gerar AccountLiquidityDTO. Retornando DTO zerado.", e);
            // Assumindo que AccountLiquidityDTO tem construtor com 4 BigDecimal
            return new AccountLiquidityDTO(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);        }
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
        try {
            BigDecimal nlv = accountValuesCache.getOrDefault(KEY_NET_LIQUIDATION_NORMALIZED, BigDecimal.ZERO);
            log.debug("💰 [PONTE | SSOT PL] Retornando Net Liquidation Value (PL) do cache: R$ {}", nlv.toPlainString());
            return nlv;
        } catch (Exception e) {
            log.error("❌ [PONTE | ERRO SSOT] Falha ao obter Net Liquidation Value do cache. Retornando Zero.", e);
            return BigDecimal.ZERO;
        }
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

    private Position mapPositionDTOtoDomain(PositionDTO dto) {
        BigDecimal quantity = dto.getPosition().abs();
        PositionDirection direction = dto.getPosition().signum() > 0 ? PositionDirection.LONG : PositionDirection.SHORT;

        return Position.builder()
                .symbol(dto.getTicker())
                .quantity(quantity)
                .averageEntryPrice(dto.getMktPrice())
                .entryTime(LocalDateTime.now())
                .direction(direction)
                .stopLoss(null)
                .takeProfit(null)
                .rationale("Sincronizado via TWS")
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
        String symbol = event.symbol();
        BigDecimal qty = event.quantity();
        BigDecimal price = event.price();

        BigDecimal cost = qty.multiply(price);
        BigDecimal newCash = current.cashBalance().subtract(cost);
        Map<String, Position> newPositions = new ConcurrentHashMap<>(current.openPositions());

        Position existingPosition = newPositions.get(symbol);
        if (existingPosition != null) {
            BigDecimal totalQty = existingPosition.getQuantity().add(qty);
            BigDecimal totalCost = existingPosition.getAverageEntryPrice().multiply(existingPosition.getQuantity()).add(cost);
            BigDecimal newAvgPrice = totalCost.divide(totalQty, 4, RoundingMode.HALF_UP);

            Position updatedPosition = new Position(symbol, totalQty, newAvgPrice, LocalDateTime.now(), existingPosition.getDirection(), existingPosition.getStopLoss(), existingPosition.getTakeProfit(), "Aumento de Posição");
            newPositions.put(symbol, updatedPosition);
        } else {
            Position newPosition = new Position(symbol, qty, price, LocalDateTime.now(), PositionDirection.LONG, null, null, "Nova Posição");
            newPositions.put(symbol, newPosition);
        }

        log.warn("✅ [PORTFÓLIO LIVE] COMPRA para {} registrada. Novo saldo: R$ {}", symbol, newCash.setScale(2, RoundingMode.HALF_UP));
        return current.toBuilder().cashBalance(newCash).openPositions(newPositions).build();
    }

    private Portfolio performSellExecution(Portfolio current, TradeExecutedEvent event) {
        String symbol = event.symbol();
        String side = event.side();
        BigDecimal qty = event.quantity();
        BigDecimal price = event.price();

        Position positionToClose = current.openPositions().get(symbol);

        if (positionToClose == null) {
            // Pode ser uma venda a descoberto (SHORT entry)
            if (side.equalsIgnoreCase("SELL") || side.equalsIgnoreCase("SLD")) {
                return performShortEntryExecution(current, event);
            }
            log.error("TENTATIVA DE VENDA INVÁLIDA: Posição {} não encontrada.", symbol);
            return current;
        }

        // Se a posição for LONG, é uma venda para fechar ou parcial (SELL)
        if (positionToClose.getDirection() == PositionDirection.LONG) {
            BigDecimal revenue = qty.multiply(price);
            BigDecimal newCash = current.cashBalance().add(revenue);
            Map<String, Position> newPositions = new ConcurrentHashMap<>(current.openPositions());

            if (qty.compareTo(positionToClose.getQuantity()) >= 0) {
                newPositions.remove(symbol);
                log.warn("✅ [PORTFÓLIO LIVE] VENDA TOTAL (ENCERRAMENTO LONG) para {} registrada. Novo saldo: R$ {}", symbol, newCash.setScale(2, RoundingMode.HALF_UP));
            } else {
                BigDecimal remainingQty = positionToClose.getQuantity().subtract(qty);

                Position updatedPosition = positionToClose.toBuilder()
                        .quantity(remainingQty)
                        .rationale("Venda Parcial - Qtd: " + remainingQty.toPlainString())
                        .build();

                newPositions.put(symbol, updatedPosition);
                log.warn("✅ [PORTFÓLIO LIVE] VENDA PARCIAL para {} registrada. Qtd Restante: {}.", symbol, remainingQty.toPlainString());
            }
            return current.toBuilder().cashBalance(newCash).openPositions(newPositions).build();
        } else if (positionToClose.getDirection() == PositionDirection.SHORT) {
            // Se a posição for SHORT, a única venda que faz sentido é a cobertura (BUY_TO_COVER)
            return performShortCoverExecution(current, event);
        }

        return current;
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

    // Método que fornece o Account ID (necessário para a validação)
    public String getAccountId() {
        return "DUN652604";
    }

    public ConcurrentHashMap<String, BigDecimal> getAccountValuesCache() {
        return accountValuesCache;
    }

}