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

@Service
@Slf4j
@Getter
public class LivePortfolioService {

    private final AtomicReference<Portfolio> portfolioState = new AtomicReference<>();
    private final ApplicationEventPublisher eventPublisher;
    private final CountDownLatch initialSyncLatch = new CountDownLatch(1);
    private final AtomicBoolean isSynced = new AtomicBoolean(false);

    // Latch para gerenciar a espera pela sincronização de posições.
    private volatile CountDownLatch positionSyncLatch = new CountDownLatch(1);

    @Value("${trading.initial-capital:200000.0}")
    private double initialCapital;

    public LivePortfolioService(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @PostConstruct
    public void init() {
        Portfolio initialPortfolio = new Portfolio(
                "LIVE_CONSOLIDADO",
                BigDecimal.valueOf(initialCapital),
                new ConcurrentHashMap<>(),
                new ArrayList<>()
        );
        this.portfolioState.set(initialPortfolio);
        log.warn("🔄 Portfólio LIVE inicializado com capital PADRÃO. Aguardando sincronização... Capital: R$ {}", initialCapital);
    }

    // --- MÉTODOS DE SINCRONIZAÇÃO ---

    public boolean awaitInitialSync(long timeoutMillis) throws InterruptedException {
        log.info("Aguardando a primeira sincronização de saldo da corretora (timeout de {}ms)...", timeoutMillis);
        return initialSyncLatch.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    public void resetPositionSyncLatch() {
        this.positionSyncLatch = new CountDownLatch(1);
    }

    public boolean awaitPositionSync(long timeoutMillis) throws InterruptedException {
        log.info("Aguardando a sincronização de posições da corretora (timeout de {}ms)...", timeoutMillis);
        return positionSyncLatch.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    public void updateAccountValue(String key, BigDecimal value) {
        if ("BuyingPower".equalsIgnoreCase(key)) {
            portfolioState.getAndUpdate(current -> current.toBuilder()
                    .cashBalance(value)
                    .build()
            );

            if (isSynced.compareAndSet(false, true)) {
                initialSyncLatch.countDown();
                log.warn("✅ PRIMEIRA SINCRONIZAÇÃO DE SALDO COMPLETA! Poder de Compra: R$ {}. Sistema operacional.", value);
            } else {
                log.info("Sincronização de saldo contínua. Poder de Compra atualizado: R$ {}", value.toPlainString());
            }
        }
    }

    /**
     * AJUSTE: Atualiza o portfólio a partir de uma lista de PositionDTO vinda do conector.
     * A conversão para o objeto de domínio 'Position' é feita aqui dentro.
     * @param ibkrPositions A lista de DTOs de posição recebida do IBKRConnector.
     */
    public void updatePortfolioPositions(List<PositionDTO> ibkrPositions) {
        // Coleta as posições em um mapa, resolvendo conflitos de chaves duplicadas
        Map<String, Position> newPositionsMap = ibkrPositions.stream()
                .collect(Collectors.toConcurrentMap(
                        PositionDTO::getTicker,               // A chave é o ticker do ativo
                        this::mapPositionDTOtoDomain,         // A função que converte o DTO para o objeto de domínio
                        (existingValue, newValue) -> newValue // <-- FUNÇÃO DE MESCLAGEM: Se houver duplicatas, use sempre o valor novo (o mais recente)
                ));

        // Atualiza o estado do portfólio de forma atômica
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

    /**
     * Método auxiliar para converter o DTO da camada de conector para o objeto de domínio.
     */
    private Position mapPositionDTOtoDomain(PositionDTO dto) {
        BigDecimal quantity = dto.getPosition().abs();
        PositionDirection direction = dto.getPosition().signum() > 0 ? PositionDirection.LONG : PositionDirection.SHORT;
        // O campo mktPrice do DTO contém o averageCost vindo da API da IBKR
        return new Position(dto.getTicker(), quantity, dto.getMktPrice(), LocalDateTime.now(), direction, null, null, "Sincronizado via TWS");
    }

    // --- OUTROS MÉTODOS ---

    public Portfolio getLivePortfolioSnapshot() {
        return portfolioState.get();
    }

    public boolean isSynced() {
        return isSynced.get();
    }

    public BigDecimal getCurrentBuyingPower() {
        return Optional.ofNullable(portfolioState.get())
                .map(Portfolio::cashBalance)
                .orElse(BigDecimal.ZERO);
    }

    @EventListener
    public void onTradeExecuted(TradeExecutedEvent event) {
        log.info("🎧 Evento de trade recebido: Fonte [{}], Ativo [{}], Lado [{}]", event.executionSource(), event.symbol(), event.side());
        portfolioState.getAndUpdate(currentPortfolio -> {
            String side = event.side().toUpperCase();
            if (side.contains("BUY") || side.contains("BOT")) {
                return performBuyExecution(currentPortfolio, event.symbol(), event.quantity(), event.price());
            } else {
                return performSellExecution(currentPortfolio, event.symbol(), event.quantity(), event.price());
            }
        });
    }

    private Portfolio performBuyExecution(Portfolio current, String symbol, BigDecimal qty, BigDecimal price) {
        BigDecimal cost = qty.multiply(price);
        BigDecimal newCash = current.cashBalance().subtract(cost);
        Map<String, Position> newPositions = new ConcurrentHashMap<>(current.openPositions());

        Position existingPosition = newPositions.get(symbol);
        if (existingPosition != null) {
            BigDecimal totalQty = existingPosition.getQuantity().add(qty);
            BigDecimal totalCost = existingPosition.getAverageEntryPrice().multiply(existingPosition.getQuantity()).add(cost);
            BigDecimal newAvgPrice = totalCost.divide(totalQty, 4, RoundingMode.HALF_UP);
            Position updatedPosition = new Position(symbol, totalQty, newAvgPrice, LocalDateTime.now(), existingPosition.getDirection(), null, null, "Aumento de Posição");
            newPositions.put(symbol, updatedPosition);
        } else {
            Position newPosition = new Position(symbol, qty, price, LocalDateTime.now(), PositionDirection.LONG, null, null, "Nova Posição");
            newPositions.put(symbol, newPosition);
        }

        log.warn("✅ [PORTFÓLIO LIVE] COMPRA para {} registrada. Novo saldo: R$ {}", symbol, newCash.setScale(2, RoundingMode.HALF_UP));
        return new Portfolio(current.symbolForBacktest(), newCash, newPositions, current.tradeHistory());
    }

    private Portfolio performSellExecution(Portfolio current, String symbol, BigDecimal qty, BigDecimal price) {
        Position positionToClose = current.openPositions().get(symbol);
        if (positionToClose == null) {
            log.error("TENTATIVA DE VENDA INVÁLIDA: Posição {} não encontrada.", symbol);
            return current;
        }

        BigDecimal revenue = qty.multiply(price);
        BigDecimal newCash = current.cashBalance().add(revenue);
        Map<String, Position> newPositions = new ConcurrentHashMap<>(current.openPositions());

        if (qty.compareTo(positionToClose.getQuantity()) >= 0) {
            newPositions.remove(symbol);
            log.warn("✅ [PORTFÓLIO LIVE] VENDA TOTAL para {} registrada. Novo saldo: R$ {}", symbol, newCash.setScale(2, RoundingMode.HALF_UP));
        } else {
            BigDecimal remainingQty = positionToClose.getQuantity().subtract(qty);
            Position updatedPosition = new Position(symbol, remainingQty, positionToClose.getAverageEntryPrice(), positionToClose.getEntryTime(), positionToClose.getDirection(), null, null, "Venda Parcial");
            newPositions.put(symbol, updatedPosition);
            log.warn("✅ [PORTFÓLIO LIVE] VENDA PARCIAL para {} registrada. Novo saldo: R$ {}", symbol, newCash.setScale(2, RoundingMode.HALF_UP));
        }

        return new Portfolio(current.symbolForBacktest(), newCash, newPositions, current.tradeHistory());
    }
}

