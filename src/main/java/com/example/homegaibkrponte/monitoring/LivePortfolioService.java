package com.example.homegaibkrponte.monitoring;

import com.example.homegaibkrponte.connector.IBKRConnector;
import com.example.homegaibkrponte.dto.AccountLiquidityDTO;
import com.example.homegaibkrponte.dto.AccountStateDTO;
import com.example.homegaibkrponte.model.Position;
import com.example.homegaibkrponte.model.PositionDTO;
import com.example.homegaibkrponte.model.PositionDirection;
import com.example.homegaibkrponte.model.Portfolio;
import com.example.homegaibkrponte.model.TradeExecutedEvent;
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
 * Implementa a lógica de validação de Excesso de Liquidez (Regra [2025-11-03]).
 */
@Service
@Slf4j
@Getter
public class LivePortfolioService {

    private final AtomicReference<Portfolio> portfolioState = new AtomicReference<>();
    private final ApplicationEventPublisher eventPublisher;

    public record AccountBalance(BigDecimal value, LocalDateTime timestamp) {}

    private final AtomicReference<AccountBalance> lastAccountBalance =
            new AtomicReference<>(new AccountBalance(BigDecimal.ZERO, LocalDateTime.MIN));

    private final AtomicReference<CountDownLatch> accountSyncLatch =
            new AtomicReference<>(new CountDownLatch(1));

    private final AtomicBoolean isSynced = new AtomicBoolean(false);

    private volatile CountDownLatch positionSyncLatch = new CountDownLatch(1);

    // 🚨 REGRA CRÍTICA [2025-11-03]
    private static final BigDecimal MARGIN_RESERVE_MIN_PCT = new BigDecimal("0.10"); // 10%

    @Value("${trading.initial-capital:200000.0}")
    private double initialCapital;

    IBKRConnector ibkrConnector;

    // Cache para todos os valores de conta (Incluindo EL e NLV) - SSOT
    private final ConcurrentHashMap<String, BigDecimal> accountValuesCache = new ConcurrentHashMap<>();

    // 🛑 NOVO: Cache local de Excess Liquidity para permitir a lógica de comparação old/newEL.
    private final AtomicReference<BigDecimal> excessLiquidityCache = new AtomicReference<>(BigDecimal.ZERO);

    // 🛑 CORREÇÃO/NOVO: Variável faltante, inicializada como BRL (moeda brasileira) para evitar NullPointer/erro de compilação.
    // Usando AtomicReference para segurança de concorrência.
    private final AtomicReference<String> accountCurrency = new AtomicReference<>("BRL");

    // 🛑 CHAVES NORMALIZADAS (Para garantir consistência)
    private static final String KEY_NET_LIQUIDATION_NORMALIZED = "NETLIQUIDATION";
    private static final String KEY_EXCESS_LIQUIDITY_NORMALIZED = "EXCESSLIQUIDITY";
    private static final String KEY_BUYING_POWER_NORMALIZED = "BUYINGPOWER";


    // --- CHAVES DE MARGEM (Assumindo que existem no cache interno da Ponte) ---
    private static final String KEY_BUYING_POWER = "BuyingPower";
    private static final String KEY_EXCESS_LIQUIDITY = "ExcessLiquidity";
    private static final String KEY_NET_LIQUIDATION = "NetLiquidation";
    private static final String KEY_INIT_MARGIN = "InitMarginReq";
    private static final String KEY_MAINTAIN_MARGIN = "MaintMarginReq";
    private static final String KEY_AVAILABLE_FUNDS = "AvailableFunds";
    private static final String KEY_CASH_BALANCE = "CashBalance";
    private static final String KEY_CURRENCY = "Currency";



    public LivePortfolioService(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
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
     * 🌉 SINK: Recebe valores brutos da conta IBKR (BuyingPower, ExcessLiquidity, etc.).
     * Este é o PONTO CENTRAL (SSOT) para todos os valores de conta em formato BigDecimal.
     */
    public void updateAccountValue(String key, BigDecimal value) {
        try {
            // 🛑 1. NORMALIZAÇÃO DA CHAVE: Força a chave para maiúsculas antes de armazenar
            String normalizedKey = key.toUpperCase();

            // 2. ARMAZENAMENTO SSOT: Armazenamento genérico no cache da Ponte para QUALQUER CHAVE.
            accountValuesCache.put(normalizedKey, value);
            log.debug("📊 [CACHE PONTE] Valor Bruto Sincronizado: {} = R$ {}", normalizedKey, value.toPlainString());

            // --- LÓGICA DE SALDO (BUYING POWER) E LATCH ---
            if (KEY_BUYING_POWER_NORMALIZED.equalsIgnoreCase(normalizedKey)) {
                LocalDateTime now = LocalDateTime.now();
                CountDownLatch latch = accountSyncLatch.get();

                // 2a. Atualiza o snapshot local do BP e o cash balance do portfólio
                lastAccountBalance.set(new AccountBalance(value, now));
                portfolioState.getAndUpdate(current -> current.toBuilder()
                        .cashBalance(value)
                        .build()
                );

                // 2b. ✅ CORREÇÃO UNIFICADA: Dispara o latch SE ele ainda estiver esperando.
                if (latch != null && latch.getCount() > 0) { // Garantindo que o latch não é nulo antes de verificar
                    latch.countDown();
                    log.info("✅ Latch de sincronização de saldo disparado (countDown).");
                }

                // 2c. Lógica de sinalização de primeira sincronização
                if (isSynced.compareAndSet(false, true)) {
                    log.warn("✅ PRIMEIRA SINCRONIZAÇÃO DE SALDO COMPLETA! Poder de Compra: R$ {}. Sistema operacional.", value);
                } else {
                    log.info("Sincronização de saldo contínua. Poder de Compra atualizado: R$ {}", value.toPlainString());
                }
            }

            // --- LÓGICA DE DISPARO DA VALIDAÇÃO DE MARGEM CRÍTICA (EXCESS LIQUIDITY) ---
            if (KEY_EXCESS_LIQUIDITY_NORMALIZED.equalsIgnoreCase(normalizedKey)) {

                BigDecimal oldEL = excessLiquidityCache.get();
                BigDecimal newEL = value;

                // 3. Atualiza cache de EL local (para lógica de comparação)
                excessLiquidityCache.set(newEL);

                // 🚨 LOG CRÍTICO MELHORADO (Sinergia com o pedido)
                log.warn("💰 [CACHE PONTE | EXCESS_LIQUIDITY_SSOT] Valor SSOT Atualizado: R$ {} (Anterior: R$ {})",
                        newEL.toPlainString(), oldEL.toPlainString());

                // Log de mudança significativa (Sinergia com o pedido)
                if (oldEL.compareTo(BigDecimal.ZERO) == 0 && newEL.compareTo(BigDecimal.ZERO) > 0) {
                    log.info("🎉 [EL-RECOVERY] Excess Liquidity recuperado: R$ {} (era R$ {})", newEL.toPlainString(), oldEL.toPlainString());
                } else if (newEL.compareTo(BigDecimal.ZERO) == 0 && oldEL.compareTo(BigDecimal.ZERO) > 0) {
                    // ⚠️ Lembrete do problema do Buying Power R$ 0.00 que dispara o Modo de Resgate de Emergência.
                    log.error("🚨 [EL-ZEROED] Excess Liquidity zerado! ATENÇÃO: Disparar validação de margem crítica. Era R$ {}, agora R$ {}", oldEL.toPlainString(), newEL.toPlainString());
                }

                // 🚨 NOVO: Dispara a validação do Excesso de Liquidez após a atualização
                validateExcessLiquidity();
            }
            // Outras chaves de margem (NLV, EquityWithLoan, InitMarginReq, MaintMarginReq)
            // já estão salvas no accountValuesCache no ponto 2.

        } catch (Exception e) {
            log.error("❌ ERRO CRÍTICO no updateAccountValue. Falha ao processar a chave {}: {}", key, e.getMessage(), e);
        }
    }

    /**
     * 🌉 SINK: Recebe a moeda da conta IBKR e armazena no SSOT de forma thread-safe.
     * @param currency O código da moeda (e.g., "BRL", "USD").
     */
    public void updateAccountCurrency(String currency) {
        if (currency != null && !currency.trim().isEmpty()) {
            this.accountCurrency.set(currency.trim().toUpperCase());
            log.debug("📊 [CACHE PONTE] Moeda da Conta Sincronizada: {}", this.accountCurrency.get());
        }
    }

    /**
     * ✅ [SSOT] Retorna o status completo de liquidez da conta (NLV, Cash, BP) do cache local.
     * Este método define a Fonte Única de Verdade (SSOT) estruturada para o Principal.
     *
     * @return AccountLiquidityDTO com valores explícitos.
     */
    public AccountLiquidityDTO getFullLiquidityStatus() {

        try {
            // 1. Obter valores do cache SSOT
            BigDecimal netLiquidationValue = getNetLiquidationValue();
            BigDecimal cashBalance = accountValuesCache.getOrDefault(KEY_CASH_BALANCE.toUpperCase(), BigDecimal.ZERO);
            BigDecimal excessLiquidity = getExcessLiquidity(); // <-- O EL está aqui
            BigDecimal availableFunds = accountValuesCache.getOrDefault(KEY_AVAILABLE_FUNDS.toUpperCase(), BigDecimal.ZERO);


            // 2. Definir o Buying Power de Retorno (PRIORIZANDO A SEGURANÇA E LIQUIDEZ)
            BigDecimal currentBuyingPower;

            if (excessLiquidity.compareTo(BigDecimal.ZERO) > 0) {
                // ✅ PRIORIDADE 1 (CORRETO): Se houver EL, ele é o poder de compra real.
                currentBuyingPower = excessLiquidity;
                log.debug("💰 [PONTE | BP FLUXO] Usando Excess Liquidity (EL: R$ {}) como Buying Power de referência.", currentBuyingPower.toPlainString());
            } else if (availableFunds.compareTo(BigDecimal.ZERO) > 0) {
                // ⚠️ FALLBACK 1: Se EL for zero, mas houver AvailableFunds, usar AF (menos restritivo que EL, mas ainda seguro).
                currentBuyingPower = availableFunds;
                log.warn("⚠️ [PONTE | BP FLUXO] EL ausente/zero. Usando Available Funds (AF: R$ {}) como substituto.", currentBuyingPower.toPlainString());
            } else {
                // 🚨 FALLBACK 2: Se tudo falhar (EL=0, AF=0), o BP é zero. Isso GARANTE O VETO no Principal.
                currentBuyingPower = BigDecimal.ZERO;
                log.error("❌ [PONTE | BP FLUXO] Liquidez (EL/AF) zerada. Retornando R$ 0.00 para forçar VETO de Emergência no Principal.");
            }

            // 3. Montar e Retornar o DTO
            AccountLiquidityDTO liquidityDTO = new AccountLiquidityDTO(
                    netLiquidationValue,
                    cashBalance,
                    currentBuyingPower, // BP Corrigido
                    excessLiquidity     // <-- INCLUSÃO CRÍTICA DO EL NO DTO DE RESPOSTA
            );

            log.info("✅ [PONTE | DTO SSOT] DTO de Liquidez Enviado. NLV: R$ {}, Cash: R$ {}, BP Retornado: R$ {}, EL: R$ {}",
                    liquidityDTO.getNetLiquidationValue().toPlainString(),
                    liquidityDTO.getCashBalance().toPlainString(),
                    liquidityDTO.getCurrentBuyingPower().toPlainString(),
                    liquidityDTO.getExcessLiquidity().toPlainString() // <-- Log do novo campo
            );

            return liquidityDTO;

        } catch (Exception e) {
            log.error("❌ ERRO CRÍTICO ao gerar AccountLiquidityDTO. Retornando DTO zerado.", e);
            // O construtor com 4 argumentos é esperado agora
            return new AccountLiquidityDTO(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    // =========================================================================
    // ✅ MÉTODOS DE ATUALIZAÇÃO E ACESSO DO SSOT
    // =========================================================================

    /**
     * 📥 Atualiza o Net Liquidation Value (PL) no cache SSOT da Ponte.
     * @param nlv O valor do Net Liquidation Value a ser armazenado.
     */
    public void updateNetLiquidationValueFromCallback(BigDecimal nlv) {
        try {
            if (nlv != null && nlv.compareTo(BigDecimal.ZERO) > 0) {
                accountValuesCache.put(KEY_NET_LIQUIDATION_NORMALIZED, nlv); // Usa chave normalizada
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
        // 🛑 CORREÇÃO: Usa a chave normalizada para GARANTIR a leitura.
        BigDecimal el = accountValuesCache.getOrDefault(KEY_EXCESS_LIQUIDITY_NORMALIZED, BigDecimal.ZERO);

        // Log para rastrear o valor que o Adaptador realmente pega
        log.debug("✅ [PONTE | GET EL] Retornando Excess Liquidity do cache SSOT: R$ {}", el.toPlainString());
        return el;
    }

    /**
     * Retorna o valor bruto do Buying Power do cache local.
     */
    // Mantido para o contrato, mas o método acima é o que define o SSOT para o Principal
    public BigDecimal getCurrentBuyingPower() {
        BigDecimal cachedBuyingPower = lastAccountBalance.get().value();
        BigDecimal cachedExcessLiquidity = getExcessLiquidity();

        // 🚨 AJUSTE: O ajuste de sinergia é mantido, mas com EL=0 ele não será acionado.
        // A lógica de SSOT em getFullLiquidityStatus é a que deve ser usada pelo Principal.
        if (cachedBuyingPower.compareTo(BigDecimal.ZERO) == 0 && cachedExcessLiquidity.compareTo(BigDecimal.ZERO) > 0) {
            log.warn("🚨 [AJUSTE SINERGIA BP] BP lido como R$0.00. Retornando ExcessLiquidity (R$ {}) para evitar o VETO de Emergência no Principal.", cachedExcessLiquidity.toPlainString());
            return cachedExcessLiquidity;
        }

        // Se o EL for 0, retorna 0.
        if (cachedExcessLiquidity.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        return cachedBuyingPower;
    }

    /**
     * Retorna o Net Liquidation Value (PL) do cache SSOT da Ponte.
     * @return O valor do NLV, ou zero se não estiver populado.
     */
    public BigDecimal getNetLiquidationValue() {
        try {
            // 🛑 CORREÇÃO: Usa a chave normalizada para GARANTIR a leitura.
            BigDecimal nlv = accountValuesCache.getOrDefault(KEY_NET_LIQUIDATION_NORMALIZED, BigDecimal.ZERO);
            log.debug("💰 [PONTE | SSOT PL] Retornando Net Liquidation Value (PL) do cache: R$ {}", nlv.toPlainString());
            return nlv;
        } catch (Exception e) {
            log.error("❌ [PONTE | ERRO SSOT] Falha ao obter Net Liquidation Value do cache. Retornando Zero.", e);
            return BigDecimal.ZERO;
        }
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
            Map<String, Position> newPositions = new ConcurrentHashMap<>(currentPortfolio.openPositions());
            newPositions.put(updatedPosition.getSymbol(), updatedPosition);

            log.warn("🔄 [LIVE PORTFOLIO] Posição {} atualizada na memória (SL/TP ou Média).", updatedPosition.getSymbol());

            return currentPortfolio.toBuilder()
                    .openPositions(newPositions)
                    .build();
        });
    }

    public boolean isSynced() {
        return isSynced.get();
    }

    public AccountBalance getLastBuyingPowerSnapshot() {
        return lastAccountBalance.get();
    }

    // =========================================================================
    // ✅ MÉTODOS DE ACESSO A MARGEM CRÍTICA (CORRIGIDO PARA O CACHE)
    // =========================================================================

    /**
     * Obtém o Capital com Valor de Empréstimo (Equity With Loan)
     * e o converte para BigDecimal.
     */
    public BigDecimal getEquityWithLoan() {
        // Assume que 'EQUITYWITHLOAN' é a chave normalizada
        return accountValuesCache.getOrDefault("EQUITYWITHLOAN", BigDecimal.ZERO);
    }

    /**
     * Obtém a Margem Inicial Requerida (Initial Margin Requirement)
     * e a converte para BigDecimal.
     */
    public BigDecimal getInitialMarginRequirement() {
        // Assume que 'INITMARGINREQ' é a chave normalizada
        return accountValuesCache.getOrDefault("INITMARGINREQ", BigDecimal.ZERO);
    }

    /**
     * Obtém a Margem de Manutenção Requerida (Maintenance Margin Requirement)
     * e a converte para BigDecimal.
     */
    public BigDecimal getMaintMarginRequirement() {
        // Assume que 'MAINTMARGINREQ' é a chave normalizada
        return accountValuesCache.getOrDefault("MAINTMARGINREQ", BigDecimal.ZERO);
    }

    /**
     * 🚨 Implementação da Regra de Excesso de Liquidez [2025-11-03].
     * Deve ser chamada sempre que os dados de margem forem atualizados (e.g., no updateAccountValue).
     */
    public void validateExcessLiquidity() {
        try {
            // 1. Obter valores de margem do cache
            BigDecimal excessLiquidity = getExcessLiquidity();
            BigDecimal maintMargin = getMaintMarginRequirement();

            // Logs explicativos para rastrear o que está acontecendo (Obrigatório)
            log.debug("🔄 [Ponte | VALIDAÇÃO MARGEM] EL: R$ {}, MaintMargin: R$ {}",
                    excessLiquidity.toPlainString(), maintMargin.toPlainString());

            // 2. O ALERTA (que a Ponte deve monitorar) é se o ExcessLiquidity (Reserva) é baixo.
            if (excessLiquidity.compareTo(BigDecimal.ZERO) <= 0) {
                log.error("🚨 [Ponte | ALERTA CRÍTICO] Excesso de Liquidez NULO ou NEGATIVO! R$ {}. Ação imediata necessária.", excessLiquidity);
            } else {
                // Se a Margem de Manutenção for o denominador da reserva.
                if (maintMargin.compareTo(BigDecimal.ZERO) > 0) {
                    // Divide Excess Liquidity pela Margem de Manutenção para obter o índice de reserva.
                    // Usamos RoundingMode.HALF_UP para evitar exceção de divisão não exata.
                    BigDecimal reserveRatio = excessLiquidity.divide(maintMargin, 4, RoundingMode.HALF_UP);

                    // Checa se a taxa de reserva é inferior a 10% (0.10)
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

        // Lógica de atualização de portfólio atômica (Princípio da Imutabilidade)
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

        // ✅ CORRIGIDO: Usando o builder para criar Position
        return Position.builder()
                .symbol(dto.getTicker())
                .quantity(quantity)
                .averageEntryPrice(dto.getMktPrice())
                .entryTime(LocalDateTime.now())
                .direction(direction)
                .stopLoss(null) // Campos opcionais explicitamente nulos
                .takeProfit(null)
                .rationale("Sincronizado via TWS")
                .build();
    }

    // 🚨 Métodos perform* agora aceitam apenas TradeExecutedEvent

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
                // tradeHistory() é mantido, mas o builder lida com ele
                .build();
    }

    private Portfolio performShortCoverExecution(Portfolio current, TradeExecutedEvent event) {
        String symbol = event.symbol();
        BigDecimal qty = event.quantity();
        BigDecimal price = event.price();

        Position positionToClose = current.openPositions().get(symbol);

        BigDecimal cost = qty.multiply(price);
        BigDecimal revenue = positionToClose.getQuantity().multiply(positionToClose.getAverageEntryPrice());

        BigDecimal newCash = current.cashBalance().subtract(cost);
        Map<String, Position> newPositions = new ConcurrentHashMap<>(current.openPositions());

        if (qty.compareTo(positionToClose.getQuantity()) >= 0) {
            // BigDecimal profitAndLoss = revenue.subtract(cost); // P&L para short é mais complexo; apenas remove a posição
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

            // Mantendo SL/TP existente para aumento de posição
            Position updatedPosition = new Position(symbol, totalQty, newAvgPrice, LocalDateTime.now(), existingPosition.getDirection(), existingPosition.getStopLoss(), existingPosition.getTakeProfit(), "Aumento de Posição");
            newPositions.put(symbol, updatedPosition);
        } else {
            // NOTA: Nova posição sem SL/TP; será anexado no updatePosition
            Position newPosition = new Position(symbol, qty, price, LocalDateTime.now(), PositionDirection.LONG, null, null, "Nova Posição");
            newPositions.put(symbol, newPosition);
        }

        log.warn("✅ [PORTFÓLIO LIVE] COMPRA para {} registrada. Novo saldo: R$ {}", symbol, newCash.setScale(2, RoundingMode.HALF_UP));
        return current.toBuilder().cashBalance(newCash).openPositions(newPositions).build();
    }

    private Portfolio performSellExecution(Portfolio current, TradeExecutedEvent event) {
        String symbol = event.symbol();
        BigDecimal qty = event.quantity();
        BigDecimal price = event.price();

        Position positionToClose = current.openPositions().get(symbol);

        if (positionToClose == null) {
            log.error("TENTATIVA DE VENDA INVÁLIDA: Posição {} não encontrada.", symbol);
            return current;
        }

        // Se a posição for LONG, é uma venda para fechar ou parcial (SELL)
        if (positionToClose.getDirection() == PositionDirection.LONG) {
            BigDecimal revenue = qty.multiply(price);
            BigDecimal newCash = current.cashBalance().add(revenue);
            Map<String, Position> newPositions = new ConcurrentHashMap<>(current.openPositions());

            if (qty.compareTo(positionToClose.getQuantity()) >= 0) {
                // Venda Total
                newPositions.remove(symbol);
                log.warn("✅ [PORTFÓLIO LIVE] VENDA TOTAL (ENCERRAMENTO LONG) para {} registrada. Novo saldo: R$ {}", symbol, newCash.setScale(2, RoundingMode.HALF_UP));
            } else {
                // Venda Parcial
                BigDecimal remainingQty = positionToClose.getQuantity().subtract(qty);

                Position updatedPosition = positionToClose.toBuilder()
                        .quantity(remainingQty)
                        .rationale("Venda Parcial - Qtd: " + remainingQty.toPlainString())
                        .build();

                newPositions.put(symbol, updatedPosition);
                log.warn("✅ [PORTFÓLIO LIVE] VENDA PARCIAL para {} registrada. Novo saldo: R$ {}", symbol, newCash.setScale(2, RoundingMode.HALF_UP));
            }
            return current.toBuilder().cashBalance(newCash).openPositions(newPositions).build();
        } else if (positionToClose.getDirection() == PositionDirection.SHORT) {
            // Se a posição for SHORT, é uma nova entrada de short (SELL/SLD)
            return performShortEntryExecution(current, event);
        }

        return current;
    }

    public AccountStateDTO getFullAccountState(String accountId) {
        log.warn("➡️ [Ponte | SYNC SSOT] Recebida requisição de AccountState completo. Disparando AccountSummary para dados frescos.");

        // 1. FORÇA O REFRESH DO TWS (Assíncrono): Garante que os valores mais frescos estejam chegando via callbacks.
        ibkrConnector.requestAccountSummarySnapshot();

        // 2. MONTAGEM DO DTO A PARTIR DO CACHE INTERNO
        AccountStateDTO dto = AccountStateDTO.builder()
                .netLiquidation(accountValuesCache.getOrDefault(KEY_NET_LIQUIDATION_NORMALIZED, BigDecimal.ZERO))
                .cashBalance(accountValuesCache.getOrDefault("CASHBALANCE", BigDecimal.ZERO))
                // O Buying Power, por segurança, muitas vezes é o NLV se não estiver explícito/liberado.
                .buyingPower(accountValuesCache.getOrDefault(KEY_BUYING_POWER_NORMALIZED,
                        accountValuesCache.getOrDefault(KEY_NET_LIQUIDATION_NORMALIZED, BigDecimal.ZERO)))
                .excessLiquidity(accountValuesCache.getOrDefault(KEY_EXCESS_LIQUIDITY_NORMALIZED, BigDecimal.ZERO))
                .initMarginReq(accountValuesCache.getOrDefault("INITMARGINREQ", BigDecimal.ZERO))
                .maintainMarginReq(accountValuesCache.getOrDefault("MAINTMARGINREQ", BigDecimal.ZERO))
                .availableFunds(accountValuesCache.getOrDefault("AVAILABLEFUNDS", BigDecimal.ZERO))
                // 🛑 CORREÇÃO DA LINHA 637: Usando o AtomicReference declarado para obter a moeda.
                .currency(accountCurrency.get())
                .timestamp(Instant.now())
                .build();

        log.info("⬅️ [Ponte | SSOT COMPILADO] AccountState DTO pronto para o Principal. NLV: R$ {}, BP: R$ {}, Moeda: {}",
                dto.netLiquidation().toPlainString(), dto.buyingPower().toPlainString(), dto.currency());

        return dto;
    }

    // Método que fornece o Account ID (necessário para a validação)
    public String getAccountId() {
        // ID da conta conforme a informação salva
        return "DUN652604";
    }

}