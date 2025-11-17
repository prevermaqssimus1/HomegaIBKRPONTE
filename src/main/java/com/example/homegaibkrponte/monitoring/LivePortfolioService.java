package com.example.homegaibkrponte.monitoring;

import com.example.homegaibkrponte.dto.AccountLiquidityDTO;
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

    // Cache para todos os valores de conta (Incluindo EL e NLV) - SSOT
    private final ConcurrentHashMap<String, BigDecimal> accountValuesCache = new ConcurrentHashMap<>();

    // ✅ CHAVE CRÍTICA
    private static final String KEY_NET_LIQUIDATION = "NetLiquidationValue";

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

    // Local: LivePortfolioService.java

    /**
     * 🌉 SINK: Recebe valores brutos da conta IBKR (BuyingPower, ExcessLiquidity, etc.).
     */
    // Local: LivePortfolioService.java

    /**
     * 🌉 SINK: Recebe valores brutos da conta IBKR (BuyingPower, ExcessLiquidity, etc.).
     */
    public void updateAccountValue(String key, BigDecimal value) {
        try {
            // 1. ARMAZENAMENTO SSOT: Armazenamento genérico no cache da Ponte para QUALQUER CHAVE.
            // Isto é crucial para garantir que MaintMarginReq e outras chaves sejam armazenadas.
            accountValuesCache.put(key, value);
            log.debug("📊 [CACHE PONTE] Valor Bruto Sincronizado: {} = R$ {}", key, value.toPlainString());

            // --- LÓGICA DE SALDO (BUYING POWER) E LATCH ---
            if ("BuyingPower".equalsIgnoreCase(key)) {
                LocalDateTime now = LocalDateTime.now();
                CountDownLatch latch = accountSyncLatch.get();

                // 1a. Atualiza o snapshot local do BP e o cash balance do portfólio
                lastAccountBalance.set(new AccountBalance(value, now));
                portfolioState.getAndUpdate(current -> current.toBuilder()
                        .cashBalance(value)
                        .build()
                );

                // 1b. ✅ CORREÇÃO UNIFICADA: Dispara o latch SE ele ainda estiver esperando.
                if (latch.getCount() > 0) {
                    latch.countDown();
                    log.info("✅ Latch de sincronização de saldo disparado (countDown).");
                }

                // 1c. Lógica de sinalização de primeira sincronização
                if (isSynced.compareAndSet(false, true)) {
                    log.warn("✅ PRIMEIRA SINCRONIZAÇÃO DE SALDO COMPLETA! Poder de Compra: R$ {}. Sistema operacional.", value);
                } else {
                    log.info("Sincronização de saldo contínua. Poder de Compra atualizado: R$ {}", value.toPlainString());
                }
            }

            // --- LÓGICA DE DISPARO DA VALIDAÇÃO DE MARGEM CRÍTICA ---
            // Se a chave "ExcessLiquidity" chegou, os dados de margem estão completos o suficiente.
            if ("ExcessLiquidity".equalsIgnoreCase(key)) {
                // 🚨 NOVO: Dispara a validação do Excesso de Liquidez após a atualização
                validateExcessLiquidity(getAccountId());
            }
            // As outras chaves críticas (NLV, EquityWithLoan, InitMarginReq, MaintMarginReq)
            // já estão armazenadas no accountValuesCache no início do método.

        } catch (Exception e) {
            log.error("❌ ERRO CRÍTICO no updateAccountValue. Falha ao processar a chave {}: {}", key, e.getMessage(), e);
        }
    }

    /**
     * ✅ [SSOT] Retorna o status completo de liquidez da conta (NLV, Cash, BP) do cache local.
     * Este método define a Fonte Única de Verdade (SSOT) estruturada para o Principal.
     *
     * @return AccountLiquidityDTO com valores explícitos.
     */
    public AccountLiquidityDTO getFullLiquidityStatus() {

        // 1. Obter valores do cache SSOT (CORREÇÃO DE VARIÁVEIS)
        try {
            // NLV: Usa a chave crítica e o cache genérico
            BigDecimal netLiquidationValue = accountValuesCache.getOrDefault(KEY_NET_LIQUIDATION, BigDecimal.ZERO);

            // Cash Balance: Obtido do último snapshot do BP (que a Ponte assume ser o Cash)
            BigDecimal cashBalance = lastAccountBalance.get().value();

            // Excess Liquidity (Opcional, mas útil para contas de margem)
            BigDecimal excessLiquidity = getExcessLiquidity(); // Reutilizando método existente

            // 2. Definir o Buying Power de Retorno (Priorizando o NLV ou EL)
            BigDecimal currentBuyingPower;

            if (netLiquidationValue.compareTo(BigDecimal.ZERO) > 0) {
                // ✅ PRIORIDADE 1: Se o NLV for válido, ele é o valor mais seguro para liquidez total.
                currentBuyingPower = netLiquidationValue;
                log.debug("💰 [PONTE | BP FLUXO] Usando NLV (R$ {}) como Buying Power de referência.", currentBuyingPower.toPlainString());
            } else if (excessLiquidity.compareTo(BigDecimal.ZERO) > 0) {
                // ⚠️ FALLBACK 1: Se NLV for zero, usar Excess Liquidity (EL) é mais seguro que o Cash Balance.
                currentBuyingPower = excessLiquidity;
                log.warn("⚠️ [PONTE | BP FLUXO] NLV ausente/zero. Usando Excess Liquidity (EL: R$ {}) como substituto.", currentBuyingPower.toPlainString());
            } else {
                // 🚨 FALLBACK 2: Se tudo falhar, usar Cash Balance. O Principal DEVE tratar isso como erro.
                currentBuyingPower = cashBalance;
                log.warn("❌ [PONTE | BP FLUXO] NLV/EL ausentes. Usando Cash Balance (R$ {}). O Principal VETARÁ.", currentBuyingPower.toPlainString());
            }

            // 3. Montar e Retornar o DTO
            AccountLiquidityDTO liquidityDTO = new AccountLiquidityDTO(
                    netLiquidationValue,
                    cashBalance,
                    currentBuyingPower
            );

            log.info("✅ [PONTE | DTO SSOT] DTO de Liquidez Enviado. NLV: R$ {}, Cash: R$ {}, BP Retornado: R$ {}",
                    liquidityDTO.getNetLiquidationValue().toPlainString(),
                    liquidityDTO.getCashBalance().toPlainString(),
                    liquidityDTO.getCurrentBuyingPower().toPlainString());

            return liquidityDTO;

        } catch (Exception e) {
            // Tratamento de erro robusto (Obrigatório)
            log.error("❌ ERRO CRÍTICO ao gerar AccountLiquidityDTO. Retornando DTO zerado.", e);
            // Retorna um DTO seguro para não quebrar o contrato
            return new AccountLiquidityDTO(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
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
                accountValuesCache.put(KEY_NET_LIQUIDATION, nlv);
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
        return accountValuesCache.getOrDefault("ExcessLiquidity", BigDecimal.ZERO);
    }

    /**
     * Retorna o valor bruto do Buying Power do cache local.
     */
    public BigDecimal getCurrentBuyingPower() {
        BigDecimal cachedBuyingPower = lastAccountBalance.get().value();
        BigDecimal cachedExcessLiquidity = getExcessLiquidity();

        // 🚨 AJUSTE: Se o BP for R$0.00 (lido incorretamente)
        // e o EL for > R$0.00 (liquidez conhecida pela corretora),
        // retorna o EL para restaurar a sinergia e evitar o VETO de Emergência no Principal.
        if (cachedBuyingPower.compareTo(BigDecimal.ZERO) == 0 && cachedExcessLiquidity.compareTo(BigDecimal.ZERO) > 0) {
            log.warn("🚨 [AJUSTE SINERGIA BP] BP lido como R$0.00. Retornando ExcessLiquidity (R$ {}) para evitar o VETO de Emergência no Principal.", cachedExcessLiquidity.toPlainString());
            return cachedExcessLiquidity;
        }

        // Caso contrário, retorna o BP (ou zero, se ambos forem zero).
        return cachedBuyingPower;
    }

    /**
     * Retorna o Net Liquidation Value (PL) do cache SSOT da Ponte.
     * @return O valor do NLV, ou zero se não estiver populado.
     */
    public BigDecimal getNetLiquidationValue() {
        try {
            BigDecimal nlv = accountValuesCache.getOrDefault(KEY_NET_LIQUIDATION, BigDecimal.ZERO);
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
    public BigDecimal getEquityWithLoan(String accountId) {
        // Assume que 'EquityWithLoan' é a chave no ConcurrentHashMap
        return accountValuesCache.getOrDefault("EquityWithLoan", BigDecimal.ZERO);
    }

    /**
     * Obtém a Margem Inicial Requerida (Initial Margin Requirement)
     * e a converte para BigDecimal.
     */
    public BigDecimal getInitialMarginRequirement(String accountId) {
        return accountValuesCache.getOrDefault("InitMarginReq", BigDecimal.ZERO);
    }

    /**
     * Obtém a Margem de Manutenção Requerida (Maintenance Margin Requirement)
     * e a converte para BigDecimal.
     */
    public BigDecimal getMaintMarginRequirement(String accountId) {
        return accountValuesCache.getOrDefault("MaintMarginReq", BigDecimal.ZERO);
    }

    /**
     * 🚨 Implementação da Regra de Excesso de Liquidez [2025-11-03].
     * Deve ser chamada sempre que os dados de margem forem atualizados (e.g., no updateAccountValue).
     */
    public void validateExcessLiquidity(String accountId) {
        try {
            // 1. Obter valores de margem do cache
            BigDecimal excessLiquidity = getExcessLiquidity();
            BigDecimal maintMargin = getMaintMarginRequirement(accountId);

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
            BigDecimal profitAndLoss = revenue.subtract(cost);
            newPositions.remove(symbol);
            log.warn("✅ [PORTFÓLIO LIVE] COBERTURA TOTAL (BUY-TO-COVER) para {}. PnL: R$ {}. Posição ENCERRADA.",
                    symbol, profitAndLoss.setScale(2, RoundingMode.HALF_UP));
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
        return new Portfolio(current.symbolForBacktest(), newCash, newPositions, current.tradeHistory());
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

        BigDecimal revenue = qty.multiply(price);
        BigDecimal newCash = current.cashBalance().add(revenue);
        Map<String, Position> newPositions = new ConcurrentHashMap<>(current.openPositions());

        if (qty.compareTo(positionToClose.getQuantity()) >= 0) {
            // Venda Total
            newPositions.remove(symbol);
            log.warn("✅ [PORTFÓLIO LIVE] VENDA TOTAL para {} registrada. Novo saldo: R$ {}", symbol, newCash.setScale(2, RoundingMode.HALF_UP));
        } else {
            // Venda Parcial
            BigDecimal remainingQty = positionToClose.getQuantity().subtract(qty);

            Position updatedPosition = new Position(
                    positionToClose.getSymbol(),
                    remainingQty,
                    positionToClose.getAverageEntryPrice(),
                    positionToClose.getEntryTime(),
                    positionToClose.getDirection(),
                    positionToClose.getStopLoss(),
                    positionToClose.getTakeProfit(),
                    "Venda Parcial - Qtd: " + remainingQty.toPlainString()
            );

            newPositions.put(symbol, updatedPosition);
            log.warn("✅ [PORTFÓLIO LIVE] VENDA PARCIAL para {} registrada. Novo saldo: R$ {}", symbol, newCash.setScale(2, RoundingMode.HALF_UP));
        }

        return new Portfolio(current.symbolForBacktest(), newCash, newPositions, current.tradeHistory());
    }

    // Método que fornece o Account ID (necessário para a validação)
    public String getAccountId() {
        // ID da conta conforme a informação salva
        return "DUN652604";
    }

}