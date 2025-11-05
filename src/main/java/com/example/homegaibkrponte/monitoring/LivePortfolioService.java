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

    /**
     * ✅ NOVO: Record para armazenar o valor e o timestamp (Java 21)
     */
    public record AccountBalance(BigDecimal value, LocalDateTime timestamp) {}

    // 🚨 CORREÇÃO 1: Cache para fallback do Buying Power, agora com timestamp para frescor.
    private final AtomicReference<AccountBalance> lastAccountBalance =
            new AtomicReference<>(new AccountBalance(BigDecimal.ZERO, LocalDateTime.MIN));

    // 🚨 CORREÇÃO 2: Latch mutável para sincronização de Saldo em tempo real.
    private final AtomicReference<CountDownLatch> accountSyncLatch =
            new AtomicReference<>(new CountDownLatch(1));

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
        // Inicializa o cache com o capital inicial e o timestamp atual (para o primeiro fallback).
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

    /**
     * 🚪 Reinicia o sinalizador de sincronização de saldo para permitir uma nova espera.
     */
    public void resetAccountSyncLatch() {
        // Usa getAndUpdate para garantir thread-safety ao verificar e substituir a latch
        accountSyncLatch.getAndUpdate(currentLatch -> {
            if (currentLatch.getCount() == 0) {
                log.debug("🔄 Sinalizador de sincronização de saldo resetado.");
                return new CountDownLatch(1);
            }
            return currentLatch; // Retorna a latch atual se não precisar de reset
        });
    }

    public boolean awaitInitialSync(long timeoutMillis) throws InterruptedException {
        // Obtém a latch atual e espera por ela
        CountDownLatch latch = accountSyncLatch.get();
        log.info("Aguardando a sincronização de saldo da corretora (timeout de {}ms)...", timeoutMillis);
        return latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Recebe o valor de saldo já convertido (BigDecimal) do IBKRConnector.
     * REMOVIDO @Override, pois esta classe não implementa EWrapper.
     */
    public void updateAccountValue(String key, BigDecimal value) {
        if ("BuyingPower".equalsIgnoreCase(key)) {
            LocalDateTime now = LocalDateTime.now();

            // 🚨 1. Atualiza o cache e o estado do Portfolio com o novo valor e o timestamp
            lastAccountBalance.set(new AccountBalance(value, now));

            portfolioState.getAndUpdate(current -> current.toBuilder()
                    .cashBalance(value)
                    .build()
            );

            // 🚨 2. Libera a Latch
            CountDownLatch latch = accountSyncLatch.get();
            latch.countDown(); // Libera a latch para requisições subsequentes e a inicial

            if (isSynced.compareAndSet(false, true)) {
                log.warn("✅ PRIMEIRA SINCRONIZAÇÃO DE SALDO COMPLETA! Poder de Compra: R$ {}. Sistema operacional.", value);
            } else {
                log.info("Sincronização de saldo contínua. Poder de Compra atualizado: R$ {}", value.toPlainString());
            }
        }
    }

    // --- MÉTODOS DE SINCRONIZAÇÃO DE POSIÇÕES (MANTIDOS) ---

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

    // --- MÉTODOS DE ACESSO ---

    public Portfolio getLivePortfolioSnapshot() {
        return portfolioState.get();
    }

    public boolean isSynced() {
        return isSynced.get();
    }

    /**
     * 🚨 AJUSTE 3: Retorna o valor do cache (usado no Controller como fallback)
     */
    public BigDecimal getCurrentBuyingPower() {
        return lastAccountBalance.get().value();
    }

    /**
     * ✅ NOVO: Retorna o snapshot completo (valor + timestamp).
     * Usado pelo Controller para verificar a validade/frescor do cache.
     */
    public AccountBalance getLastBuyingPowerSnapshot() {
        return lastAccountBalance.get();
    }

    // --- LÓGICA DE EXECUÇÃO DE TRADE (MANTIDA) ---

    @EventListener
    public void onTradeExecuted(TradeExecutedEvent event) {
        try {
            log.info("🎧 Evento de trade recebido: Fonte [{}], Ativo [{}], Lado [{}]", event.executionSource(), event.symbol(), event.side());

            portfolioState.getAndUpdate(currentPortfolio -> {
                String side = event.side().toUpperCase();

                // 1. Identifica a posição existente
                Position existingPosition = currentPortfolio.openPositions().get(event.symbol());

                // 2. Lógica de Roteamento Baseada no Lado do Evento
                if (side.contains("BUY") || side.contains("BOT")) {
                    if (existingPosition != null && existingPosition.getDirection() == PositionDirection.SHORT) {
                        return performShortCoverExecution(currentPortfolio, event.symbol(), event.quantity(), event.price());
                    } else {
                        return performBuyExecution(currentPortfolio, event.symbol(), event.quantity(), event.price());
                    }
                } else if (side.contains("SELL") || side.contains("SLD")) {
                    if (existingPosition != null && existingPosition.getDirection() == PositionDirection.LONG) {
                        return performSellExecution(currentPortfolio, event.symbol(), event.quantity(), event.price());
                    } else {
                        return performShortEntryExecution(currentPortfolio, event.symbol(), event.quantity(), event.price());
                    }
                }

                log.error("❌ [ROTEAMENTO ERRO] Lado de execução desconhecido/não tratado: {}", side);
                return currentPortfolio; // Retorna o estado atual no lambda em caso de erro
            });
        } catch (Exception e) {
            log.error("❌ ERRO CRÍTICO ao processar TradeExecutedEvent para {}. Causa: {}.", event.symbol(), e.getMessage(), e);
        }
    }

    // --- MÉTODOS PRIVADOS DE DOMÍNIO (MANTIDOS) ---

    private Position mapPositionDTOtoDomain(PositionDTO dto) {
        BigDecimal quantity = dto.getPosition().abs();
        PositionDirection direction = dto.getPosition().signum() > 0 ? PositionDirection.LONG : PositionDirection.SHORT;
        // O campo mktPrice do DTO contém o averageCost vindo da API da IBKR
        return new Position(dto.getTicker(), quantity, dto.getMktPrice(), LocalDateTime.now(), direction, null, null, "Sincronizado via TWS");
    }

    private Portfolio performShortEntryExecution(Portfolio current, String symbol, BigDecimal qty, BigDecimal price) {
        BigDecimal cost = qty.multiply(price);
        BigDecimal newCash = current.cashBalance().add(cost);
        Map<String, Position> newPositions = new ConcurrentHashMap<>(current.openPositions());

        Position newPosition = new Position(symbol, qty, price, LocalDateTime.now(), PositionDirection.SHORT, null, null, "Venda a Descoberto");
        newPositions.put(symbol, newPosition);

        log.warn("✅ [PORTFÓLIO LIVE] NOVA VENDA A DESCOBERTO (SHORT) para {} registrada. Novo saldo: R$ {}", symbol, newCash.setScale(2, RoundingMode.HALF_UP));
        return new Portfolio(current.symbolForBacktest(), newCash, newPositions, current.tradeHistory());
    }

    private Portfolio performShortCoverExecution(Portfolio current, String symbol, BigDecimal qty, BigDecimal price) {
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
}