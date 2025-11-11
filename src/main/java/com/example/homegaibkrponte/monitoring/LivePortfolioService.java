package com.example.homegaibkrponte.monitoring;

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

    @Value("${trading.initial-capital:200000.0}")
    private double initialCapital;

    // Cache para todos os valores de conta (Incluindo EL e NLV)
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

    /**
     * 🌉 SINK: Recebe valores brutos da conta IBKR (BuyingPower, ExcessLiquidity, etc.).
     */
    public void updateAccountValue(String key, BigDecimal value) {
        // Armazenamento genérico no cache da Ponte
        accountValuesCache.put(key, value);

        if ("BuyingPower".equalsIgnoreCase(key)) {
            LocalDateTime now = LocalDateTime.now();

            lastAccountBalance.set(new AccountBalance(value, now));

            portfolioState.getAndUpdate(current -> current.toBuilder()
                    .cashBalance(value)
                    .build()
            );

            CountDownLatch latch = accountSyncLatch.get();
            latch.countDown();

            if (isSynced.compareAndSet(false, true)) {
                log.warn("✅ PRIMEIRA SINCRONIZAÇÃO DE SALDO COMPLETA! Poder de Compra: R$ {}. Sistema operacional.", value);
            } else {
                log.info("Sincronização de saldo contínua. Poder de Compra atualizado: R$ {}", value.toPlainString());
            }
        }

        // Log para rastrear os valores de margem CRÍTICOS e ARMAZENAR EL NO CACHE PRINCIPAL
        if ("ExcessLiquidity".equalsIgnoreCase(key)) {
            log.debug("📊 [CACHE PONTE] Margem Bruta Sincronizada: {} = R$ {}", key, value.toPlainString());
            accountValuesCache.put("ExcessLiquidity", value);
        } else if (KEY_NET_LIQUIDATION.equalsIgnoreCase(key)) { // ✅ AJUSTE AQUI
            log.debug("📊 [CACHE PONTE] Margem Bruta Sincronizada: {} = R$ {}", key, value.toPlainString());
            accountValuesCache.put(KEY_NET_LIQUIDATION, value);
        }
    }

    // =========================================================================
    // ✅ NOVO MÉTODO: ATUALIZAÇÃO DO NLV USANDO CHAVE CONSISTENTE
    // =========================================================================

    /**
     * 📥 Atualiza o Net Liquidation Value (PL) no cache SSOT da Ponte.
     * Este é o método que deve ser chamado pelo IBKRConnector, independentemente
     * do nome exato do campo que o TWS enviou (ex: 'NetLiquidation' ou 'NetLiquidationValue').
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


    // --- MÉTODOS PRIVADOS DE DOMÍNIO (CORRIGIDOS) ---

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

    // 🚨 AJUSTE DE ASSINATURA: Métodos perform* agora aceitam apenas TradeExecutedEvent

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
}