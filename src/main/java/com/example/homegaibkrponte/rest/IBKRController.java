package com.example.homegaibkrponte.rest;

import com.example.homegaibkrponte.connector.IBKRConnector;
import com.example.homegaibkrponte.connector.mapper.IBKRMapper;
import com.example.homegaibkrponte.dto.*;
import com.example.homegaibkrponte.model.OrderStateDTO;
import com.example.homegaibkrponte.model.PositionDTO;
import com.example.homegaibkrponte.monitoring.LivePortfolioService;
import com.example.homegaibkrponte.service.OrderIdManager;
import com.example.homegaibkrponte.service.OrderService;
import com.ib.client.Contract;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 🌉 Controlador REST da Ponte IBKR.
 * Centraliza a comunicação com o Principal.
 * * ✅ AJUSTE DEFINITIVO: O método /buying-power agora consome o cache sincronizado,
 * enviando NLV, BP e EL reais, eliminando os valores zerados no Principal.
 */
@RestController
@RequestMapping("/api/ibkr")
@Slf4j
public class IBKRController {

    private final IBKRConnector connector;
    private final OrderService orderService;
    private final LivePortfolioService portfolioService;
    private final IBKRMapper ibkrMapper;
    private final OrderIdManager orderIdManager;

    @Autowired
    public IBKRController(IBKRConnector connector, OrderService orderService,
                          LivePortfolioService portfolioService, IBKRMapper ibkrMapper,
                          OrderIdManager orderIdManager) {
        this.connector = connector;
        this.orderService = orderService;
        this.portfolioService = portfolioService;
        this.ibkrMapper = ibkrMapper;
        this.orderIdManager = orderIdManager;
    }

    /**
     * 🛡️ ENDPOINT DE SINERGIA T+1 (QUALQUER MARÉ)
     * Retorna a lista de ativos que foram comprados HOJE.
     * Essencial para o Principal diferenciar Estoque de Day Trade no boot.
     */
    @GetMapping("/bought-today")
    public ResponseEntity<Set<String>> getSymbolsBoughtToday() {
        log.info("📡 [PONTE | API] Requisição de inventário intraday recebida.");
        try {
            // Chama o método que implementamos no IBKRConnector
            Set<String> symbols = connector.getSymbolsBoughtToday();

            log.info("✅ [PONTE | RT-ENVIO] Ativos comprados hoje enviados ao Principal: {}", symbols);
            return ResponseEntity.ok(symbols);
        } catch (Exception e) {
            log.error("❌ [PONTE] Erro ao listar ativos comprados hoje: {}", e.getMessage());
            return ResponseEntity.ok(Collections.emptySet());
        }
    }

    /**
     * ✅ ENDPOINT CRÍTICO: Fornece a liquidez em tempo real para o Principal.
     * Agora utiliza getFullLiquidityStatus() que lê o cache normalizado (NLV/BP/EL).
     */
    @GetMapping("/buying-power")
    public ResponseEntity<AccountLiquidityDTO> getBuyingPower() {
//        log.info("💰 [PONTE | API] Requisição de liquidez streaming recebida.");

        if (!connector.isConnected()) {
            log.error("❌ [PONTE] Conexão inativa com TWS. Retornando estado anterior do cache.");
            return ResponseEntity.ok(portfolioService.getFullLiquidityStatus());
        }

        try {
            // Obtém o DTO completo que agora contém os valores sincronizados do streaming
            AccountLiquidityDTO liquidityStatus = portfolioService.getFullLiquidityStatus();

//            log.info("✅ [PONTE | RT-ENVIO] Transmitindo ao Principal -> NLV: R$ {} | BP: R$ {} | EL: R$ {}",
//                    liquidityStatus.getNetLiquidationValue().toPlainString(),
//                    liquidityStatus.getCurrentBuyingPower().toPlainString(),
//                    liquidityStatus.getExcessLiquidity().toPlainString());

            return ResponseEntity.ok(liquidityStatus);

        } catch (Exception e) {
            log.error("❌ [PONTE] Erro crítico ao servir status de liquidez: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new AccountLiquidityDTO(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<String> getStatus() {
        return connector.isConnected()
                ? ResponseEntity.ok("CONNECTED")
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("DISCONNECTED");
    }

    @GetMapping("/market-price/{symbol}")
    public ResponseEntity<BigDecimal> fetchLatestMarketPrice(@PathVariable String symbol) {
        try {
            Optional<BigDecimal> priceOpt = connector.getLatestCachedPrice(symbol);
            return priceOpt.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("❌ ERRO ao obter preço: {}", symbol, e);
            return ResponseEntity.internalServerError().body(BigDecimal.ZERO);
        }
    }

    @PostMapping("/sync")
    public ResponseEntity<Void> forceSync() {
        log.info("🔄 [PONTE] Comando de sincronização forçada (Reset de Subscrição).");
        try {
            connector.getClient().cancelAccountSummary(9001);
            connector.getClient().reqAccountUpdates(true, connector.getAccountId());
            connector.getClient().reqAccountSummary(9001, "All",
                    "NetLiquidation,EquityWithLoanValue,BuyingPower,ExcessLiquidity,InitMarginReq,MaintMarginReq");
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("❌ [PONTE] Falha no comando Sync: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/positions")
    public ResponseEntity<List<PositionDTO>> getOpenPositions() {
        if (!connector.isConnected()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        try {
            portfolioService.resetPositionSyncLatch();
            connector.getClient().reqPositions();
            boolean syncCompleted = portfolioService.awaitPositionSync(60000);

            if (!syncCompleted) return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build();

            List<PositionDTO> positions = portfolioService.getLivePortfolioSnapshot()
                    .openPositions().values().stream()
                    .map(p -> {
                        PositionDTO d = new PositionDTO();
                        d.setTicker(p.getSymbol());
                        d.setPosition(p.getQuantity());
                        d.setMktPrice(p.getAverageEntryPrice());
                        return d;
                    }).collect(Collectors.toList());

            return ResponseEntity.ok(positions);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/place-order")
    public ResponseEntity<?> placeOrder(@RequestBody OrderDTO orderDto) {
        // Se chegar aqui, o Jackson conseguiu converter. Se der 400 antes, o problema é no envio.
        log.info("🛒 [PONTE] Recebida submissão via Record: {} | Ativo: {}",
                orderDto.clientOrderId(), orderDto.symbol());

        try {
            // Chamada ao service que já conhece o record
            OrderDTO result = orderService.placeOrder(orderDto);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("💥 [PONTE] Erro ao processar Record OrderDTO: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @GetMapping("/order/next-id")
    public ResponseEntity<Integer> getNextOrderId() {
        return ResponseEntity.ok(orderIdManager.getNextOrderId());
    }

    @GetMapping("/margin/nlv")
    public ResponseEntity<BigDecimal> getNetLiquidationValue() {
        return ResponseEntity.ok(portfolioService.getNetLiquidationValue());
    }

    @PostMapping("/margin/what-if")
    public ResponseEntity<WhatIfResponseDTO> processRealTimeWhatIf(@RequestBody WhatIfRequestDTO request) {
        log.info("🔍 [PONTE | What-If] Simulação solicitada para {}", request.getSymbol());
        if (!connector.isConnected()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();

        try {
            int id = orderIdManager.getNextOrderId();
            Contract contract = ibkrMapper.toContract(request.getSymbol());
            com.ib.client.Order order = ibkrMapper.toWhatIfOrder(id, request.getSide(), request.getQuantity());

            OrderStateDTO result = connector.sendWhatIfRequest(contract, order);
            BigDecimal change = ibkrMapper.parseMarginValue(result.getInitMarginChange());
            BigDecimal currentBP = portfolioService.getFullLiquidityStatus().getCurrentBuyingPower();

            log.info("📢 [PONTE | What-If SUCESSO] Impacto: {} | BP Atual: {}", change, currentBP);
            return ResponseEntity.ok(new WhatIfResponseDTO(true, change, currentBP, null));

        } catch (Exception e) {
            log.error("🛑 [PONTE | What-If FALHA] Motivo: {}", e.getMessage());
            return ResponseEntity.ok(new WhatIfResponseDTO(false, BigDecimal.ZERO,
                    portfolioService.getExcessLiquidity(), e.getMessage()));
        }
    }

    /**
     * 🔓 ENDPOINT DE SINERGIA: Destrava o sistema de recuperação de margem.
     * Chamado pelo Principal após uma venda bem-sucedida para limpar
     * bloqueios de Circuit Breaker na Ponte.
     */
    @PostMapping("/clear-margin-faults")
    public ResponseEntity<String> clearMarginFaults(@RequestParam(required = false) String symbol) {
        try {
            if (symbol != null && !symbol.isBlank()) {
                log.warn("🔓 [PONTE | RESET] Limpando contador de falhas específico para: {}", symbol);
                connector.clearSymbolFailure(symbol); // Método a ser adicionado no IBKRConnector
                return ResponseEntity.ok("Reset de falhas concluído para " + symbol);
            } else {
                log.warn("🔓 [PONTE | RESET GLOBAL] Limpando TODOS os contadores de falha de ativos.");
                connector.clearAllFailures(); // Método a ser adicionado no IBKRConnector
                return ResponseEntity.ok("Reset global de falhas concluído.");
            }
        } catch (Exception e) {
            log.error("❌ Erro ao limpar falhas de margem: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    /**
     * 📊 ENDPOINT DE TELEMETRIA: Verifica se um ativo está em estado de bloqueio.
     */
    @GetMapping("/margin/check-fault/{symbol}")
    public ResponseEntity<Integer> getSymbolFailureCount(@PathVariable String symbol) {
        int count = connector.getFailureCount(symbol); // Método a ser adicionado no IBKRConnector
        return ResponseEntity.ok(count);
    }

    @DeleteMapping("/order/{clientOrderId}")
    public ResponseEntity<Void> cancelOrder(@PathVariable String clientOrderId) {
        log.warn("🧹 [PONTE | CANCEL] Recebida solicitação de cancelamento para: {}", clientOrderId);

        try {
            // 1. Comando via OrderService (que já tem acesso ao conector e ao mapeamento de IDs)
            // Se o seu OrderService não tiver o cancelOrder, precisaremos criar lá também.
            orderService.cancelOrder(clientOrderId);

            log.info("✅ [PONTE | CANCEL] Ordem {} cancelada com sucesso na TWS.", clientOrderId);
            return ResponseEntity.ok().build();

        } catch (Exception e) {
            log.error("❌ [PONTE | CANCEL] Erro ao processar cancelamento para {}: {}", clientOrderId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}