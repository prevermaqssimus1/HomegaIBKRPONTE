package com.example.homegaibkrponte.rest;

import com.example.homegaibkrponte.connector.IBKRConnector;
import com.example.homegaibkrponte.connector.mapper.IBKRMapper;
import com.example.homegaibkrponte.dto.*;
import com.example.homegaibkrponte.model.OrderStateDTO;
import com.example.homegaibkrponte.model.Position;
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
import java.util.stream.Collectors;

/**
 * Controlador REST principal para interações com a ponte IBKR.
 * Centraliza todos os endpoints para status, ordens e informações da conta.
 *
 * ✅ AJUSTE CRÍTICO: O método /buying-power agora usa o LivePortfolioService.getFullLiquidityStatus(),
 * que contém a Barreira de Sincronização para Margem Crítica (MaintMarginReq / InitMarginReq).
 */
@RestController
@RequestMapping("/api/ibkr")
@Slf4j
public class IBKRController {

    // Dependências mantidas como final
    private final IBKRConnector connector;
    private final OrderService orderService;
    private final LivePortfolioService portfolioService;
    private final IBKRMapper ibkrMapper;
    private final OrderIdManager orderIdManager;
    // Campo duplicado mantido como final para compatibilidade com a implementação original
    private final IBKRConnector ibkrConnector;

    @Autowired
    public IBKRController(IBKRConnector connector, OrderService orderService,
                          LivePortfolioService portfolioService, IBKRMapper ibkrMapper,
                          OrderIdManager orderIdManager, LivePortfolioService ivePortfolioService) {
        this.connector = connector;
        this.orderService = orderService;
        this.portfolioService = portfolioService;
        this.ibkrConnector = connector; // ibkrConnector é o mesmo que connector
        this.ibkrMapper = ibkrMapper;
        this.orderIdManager = orderIdManager;
    }

    /**
     * Endpoint de saúde e status da conexão com o TWS/Gateway.
     */
    @GetMapping("/status")
    public ResponseEntity<String> getStatus() {
        if (!connector.isConnected()) {
            // Tenta reconectar se estiver desconectado
            connector.connect();
        }
        return connector.isConnected()
                ? ResponseEntity.ok("CONNECTED")
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("DISCONNECTED");
    }

    @GetMapping("/market-price/{symbol}")
    public ResponseEntity<BigDecimal> fetchLatestMarketPrice(@PathVariable String symbol) {
        log.info("➡️ [PONTE | API] Requisição de preço FRESH on-demand recebida do Principal para: {}", symbol);
        try {
            // Assume que ibkrConnector na Ponte tem um método getLatestCachedPrice
            Optional<BigDecimal> priceOpt = ibkrConnector.getLatestCachedPrice(symbol);

            if (priceOpt.isEmpty()) {
                log.warn("⬅️ [PONTE | API] Preço não encontrado em cache para {}. Retornando 404.", symbol);
                return ResponseEntity.notFound().build();
            }

            log.info("⬅️ [PONTE | API] Preço FRESH retornado para {}: R$ {}", symbol, priceOpt.get().toPlainString());
            return ResponseEntity.ok(priceOpt.get());
        } catch (Exception e) {
            log.error("❌ ERRO ao obter preço de mercado on-demand para {}.", symbol, e);
            return ResponseEntity.internalServerError().body(BigDecimal.ZERO);
        }
    }

    @PostMapping("/sync")
    public ResponseEntity<Void> forceSync() {
        log.info("🔄 [Ponte | API] Comando de sincronização forçada. Limpando subscrições anteriores para evitar Erro 322.");
        try {
            // 1. Cancela subscrição anterior para evitar o limite de requisições da IBKR
            connector.getClient().cancelAccountSummary(9001);

            // 2. Solicita atualização fresca de TUDO (Conta e Posições)
            connector.getClient().reqAccountUpdates(true, connector.getAccountId());
            connector.getClient().reqAccountSummary(9001, "All",
                    "NetLiquidation,EquityWithLoanValue,BuyingPower,ExcessLiquidity,InitMarginReq,MaintMarginReq");

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("❌ [Ponte] Erro ao disparar Sync: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 🚨 NOVO ENDPOINT (SINERGIA): Força a sincronização completa dos valores de conta (BP, EL, NLV) do TWS.
     * Após a requisição, a subscrição é desativada imediatamente.
     */
    @GetMapping("/sync-account-values")
    public ResponseEntity<Void> syncAccountValues(@RequestParam String accountId) {
        if (!connector.isConnected()) {
            log.error("❌ [Ponte | SYNC] Conexão com TWS inativa. Não é possível sincronizar valores de conta.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        log.info("➡️ [Ponte | SYNC] Recebida requisição do Principal para sincronização forçada de valores de conta para {}", accountId);

        try {
            // 1. Dispara a subscrição de atualização de conta no TWS (que é assíncrona)
            connector.getClient().reqAccountUpdates(true, accountId);
            log.warn("🔄 [Ponte | SYNC] Subscrição de Account Updates enviada ao TWS. Dados serão atualizados via callback.");

            // 2. Desativa a subscrição para evitar tráfego contínuo desnecessário
            connector.getClient().reqAccountUpdates(false, accountId);
            log.info("✅ [Ponte | SYNC] Subscrição de Account Updates desativada.");

            // Retornamos OK imediatamente, pois a atualização é assíncrona.
            return ResponseEntity.ok().build();

        } catch (Exception e) {
            log.error("❌ [Ponte | SYNC] Falha ao iniciar a sincronização de valores de conta no TWS.", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * ✅ CORRIGIDO: Busca o poder de compra em tempo real, utilizando a Barreira de Sincronização.
     */
    @GetMapping("/buying-power")
    public ResponseEntity<AccountLiquidityDTO> getBuyingPower() {
        log.info("------------------------------------------------------------");
        log.info("💰 Requisição REST recebida para '/buying-power' (em tempo real).");

        if (!connector.isConnected()) {
            log.error("❌ Abortando: Conexão com a corretora não está ativa. Retornando DTO com ZEROS.");
            // RETORNO CORRIGIDO: 4 Argumentos (NLV, Cash, BP, EL)
            AccountLiquidityDTO liquidityDTO = portfolioService.getFullLiquidityStatus();
            return ResponseEntity.ok(liquidityDTO);
        }

        try {
            // 1. Dispara a atualização de saldo na Ponte (Assíncrono, sem esperar o callback)
            log.warn("⏳ Disparando 'reqAccountUpdates' (Assíncrono). Principal usará o valor mais fresco disponível.");
            connector.getClient().reqAccountUpdates(true, "All");

            // 2. Desativa a subscrição imediatamente após o disparo
            connector.getClient().reqAccountUpdates(false, "All");

            // 3. ✅ AJUSTE CRÍTICO: Retorna o DTO COMPLETO de liquidez do cache local,
            // O LivePortfolioService fará a Barreira de Sincronização da Margem.
            AccountLiquidityDTO liquidityStatus = portfolioService.getFullLiquidityStatus();

            if (liquidityStatus.getCurrentBuyingPower().compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("⚠️ Retornando BP ZERO ou negativo no DTO. Principal deve usar Lógica de Fallback/ERRO.");
            } else {
                // Logs explicativos para rastrear o que está acontecendo (Obrigatório)
                log.info("💸 Retornando DTO de Liquidez. NLV: R$ {}, Cash: R$ {}, BP: R$ {}",
                        liquidityStatus.getNetLiquidationValue().toPlainString(),
                        liquidityStatus.getCashBalance().toPlainString(),
                        liquidityStatus.getCurrentBuyingPower().toPlainString());
            }

            // Retorna o DTO estruturado
            return ResponseEntity.ok(liquidityStatus);

        } catch (Exception e) {
            // Registra o erro detalhado
            log.error("❌ ERRO INESPERADO ao processar requisição de Buying Power. Retornando DTO zerado (6 argumentos).", e);

            // ✅ CORREÇÃO: Utiliza o construtor de 6 argumentos, passando BigDecimal.ZERO para todos eles.
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new AccountLiquidityDTO(
                            BigDecimal.ZERO, // netLiquidationValue
                            BigDecimal.ZERO, // cashBalance
                            BigDecimal.ZERO, // currentBuyingPower
                            BigDecimal.ZERO, // excessLiquidity
                            BigDecimal.ZERO, // maintainMarginReq (MMR)
                            BigDecimal.ZERO  // initMarginReq (IMR)
                    ));
        } finally {
            log.info("------------------------------------------------------------");
        }
    }

    /**
     * Obtém todas as posições abertas. Utiliza um Latch para sincronizar a resposta assíncrona do TWS.
     */
    @GetMapping("/positions")
    public ResponseEntity<List<PositionDTO>> getOpenPositions() {
        log.info("------------------------------------------------------------");
        log.info("📊 Requisição REST recebida para '/positions'.");

        if (!connector.isConnected()) {
            log.error("❌ Abortando: Conexão com a corretora não está ativa.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyList());
        }

        try {
            portfolioService.resetPositionSyncLatch();

            log.info("➡️  Solicitando posições à corretora e aguardando resposta...");
            connector.getClient().reqPositions();

            // Espera até 60 segundos pela resposta do callback de posições
            boolean syncCompleted = portfolioService.awaitPositionSync(60000);

            if (!syncCompleted) {
                log.error("❌ TIMEOUT! A sincronização de posições não ocorreu em 60 segundos.");
                return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Collections.emptyList());
            }

            // Lê o portfólio atualizado do cache local
            List<PositionDTO> openPositions = portfolioService.getLivePortfolioSnapshot()
                    .openPositions()
                    .values()
                    .stream()
                    .map(this::mapPositionToDTO)
                    .collect(Collectors.toList());

            log.info("⬅️  Retornando {} posições abertas via API REST.", openPositions.size());
            return ResponseEntity.ok(openPositions);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ A thread foi interrompida enquanto esperava pela sincronização de posições.", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Collections.emptyList());
        } finally {
            log.info("------------------------------------------------------------");
        }
    }

    /**
     * ✅ CORREÇÃO FINAL DE SINERGIA: Endpoint chamado pelo Principal para forçar o Snapshot de Conta (EL/BP).
     * Dispara o requestAccountSummarySnapshot que atualiza o cache interno da Ponte.
     */
    @PostMapping("/sync-snapshot")
    public ResponseEntity<Void> triggerAccountSummarySnapshot() {
        // Aplica try-catch e logs explicativos (Obrigatório)
        try {
            log.info("🔄 [Ponte | SYNC COMANDO] Recebido comando do Principal para forçar o Account Summary Snapshot.");

            connector.requestAccountSummarySnapshot();

            log.info("✅ [Ponte | SYNC] Snapshot de Account Summary disparado no TWS. Dados serão atualizados assincronamente.");
            return ResponseEntity.accepted().build();
        } catch (Exception e) {
            log.error("❌ [Ponte | ERRO SYNC] Falha ao disparar o Account Summary Snapshot. Rastreando.", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Converte o objeto de domínio 'Position' para o 'PositionDTO' de resposta da API.
     */
    private PositionDTO mapPositionToDTO(Position position) {
        PositionDTO dto = new PositionDTO();
        dto.setTicker(position.getSymbol());
        dto.setPosition(position.getQuantity());
        // Mapeia o preço de entrada para o campo mktPrice para consistência com o que o client espera
        dto.setMktPrice(position.getAverageEntryPrice());
        return dto;
    }



    /**
     * Submete uma nova ordem ao TWS/Gateway.
     */
    @PostMapping("/place-order")
    public ResponseEntity<OrderDTO> placeOrder(@RequestBody OrderDTO orderDto) {
        // Log de Entrada - Indica o início do processamento da ordem na Ponte
        log.info("🛒 [Ponte | Controller] Recebida requisição REST para executar ordem. ClientID: {}", orderDto.clientOrderId());

        try {
            // Chamada ao serviço principal para submeter ao TWS
            OrderDTO resultDto = orderService.placeOrder(orderDto);

            // Log de Saída - Indica que a ordem foi submetida com sucesso ao TWS/Gateway
            log.info("🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀🚀 [Ponte | Controller] Ordem SUBMETIDA. ClientID: {}, ID IBKR: {}. Aguardando callbacks de status.",
                    resultDto.clientOrderId(), resultDto.orderId());

            return ResponseEntity.ok(resultDto);

        } catch (IllegalStateException e) {
            // LOG para rejeição de validação de negócio (Ex: falta de campo, validação interna)
            log.warn("🚫 [Ponte | Controller] Ordem REJEITADA (BAD_REQUEST). ClientID: {}. Motivo: {}",
                    orderDto.clientOrderId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(null);

        } catch (Exception e) {
            // LOG para erros críticos (Ex: falha de comunicação, erro de infraestrutura)
            log.error("💥 [Ponte | Controller] Erro CRÍTICO ao processar ordem. ClientID: {}. Mensagem: {}",
                    orderDto.clientOrderId(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    /**
     * Fornece o próximo ID de ordem válido para ser usado pelo Principal.
     */
    @GetMapping("/order/next-id")
    public ResponseEntity<NextOrderIdResponse> getNextOrderId() {
        try {
            int nextId = orderIdManager.getNextOrderId();
            log.info("🆔 [Ponte | Controller] Fornecendo próximo ID de ordem válido: {}", nextId);
            return ResponseEntity.ok(new NextOrderIdResponse(nextId));
        } catch (IllegalStateException e) {
            log.error("⏳ [Ponte | Controller] Tentativa de obter ID de ordem antes da inicialização.", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(null);
        }
    }

    // Classe interna para a resposta do ID da ordem
    private record NextOrderIdResponse(int nextOrderId) {}

    /**
     * Obtém o Net Liquidation Value (PL) do cache da Ponte.
     */
    @GetMapping("/margin/nlv")
    public ResponseEntity<BigDecimal> getNetLiquidationValue() {
        try {
            // NLV é espelhado pelo valor mais fresco no cache.
            BigDecimal netLiquidation = portfolioService.getNetLiquidationValue();

            if (netLiquidation.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("⚠️ [Ponte | NLV] Net Liquidation Value (NLV) retornou R$ 0.00 ou negativo. Assumindo indisponibilidade. Veto de Sizing possível no Principal.");
            }

            log.info("✅ [Ponte | NLV] Retornando Net Liquidation Value (PL) para o Principal: R$ {}", netLiquidation);
            return ResponseEntity.ok(netLiquidation);

        } catch (Exception e) {
            log.error("❌ [Ponte | ERRO NLV] Falha crítica ao obter Net Liquidation Value (PL). Forçando R$ 0.00. Rastreando.", e);
            // Retorna ZERO, forçando o veto no Sizing do Principal (Fail-safe).
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(BigDecimal.ZERO);
        }
    }

    @GetMapping("/account/state")
    public AccountStateDTO getFullAccountState(@RequestParam String accountId) {
        try {
            // Delega para o LivePortfolioService (Ponte) compilar e retornar o estado completo
            return portfolioService.getFullAccountState(accountId);
        } catch (Exception e) {
            log.error("❌ Erro CRÍTICO ao processar requisição de AccountState para {}. Rastreando.", accountId, e);
            // Retorna um DTO de falha seguro para o Principal
            return AccountStateDTO.builder().build();
        }
    }

    /**
     * ✅ MÉTODO ATUAL E CORRIGIDO: Processa a simulação What-If (POST /margin/what-if)
     * e utiliza o fluxo real assíncrono (sendWhatIfRequest) com espera síncrona.
     * Este endpoint unifica e substitui os fluxos anteriores.
     */
    @PostMapping("/margin/what-if")
    public ResponseEntity<WhatIfResponseDTO> processRealTimeWhatIf(@RequestBody WhatIfRequestDTO request) {

        log.info("➡️ [Ponte | Controller] Recebida requisição REST What-If REAL para {} (Lado: {}, Qty: {}).",
                request.getSymbol(), request.getSide(), request.getQuantity());

        // Inicializa o Buying Power. Será usado em caso de falha.
        BigDecimal realTimeBuyingPower = BigDecimal.ZERO;

        // 1. Garante que a conexão está ativa antes de prosseguir
        if (!connector.isConnected()) {
            log.error("❌ [Ponte | What-If] Conexão com TWS inativa. Retornando erro de serviço indisponível.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new WhatIfResponseDTO(
                    false,
                    BigDecimal.ZERO,
                    realTimeBuyingPower,
                    "Conexão com a corretora indisponível."
            ));
        }

        // 🚨 NOVO: Obtém o ID de ordem de forma ATÔMICA para What-If (Corrige TWS Code 103 - Duplicar ID)
        int whatIfOrderId = orderIdManager.getNextOrderId();
        log.info("ℹ️ [Ponte | What-If] Utilizando Novo ID de Ordem: {}", whatIfOrderId);

        try {
            Contract contract = ibkrMapper.toContract(request.getSymbol());
            // Cria a Ordem What-If a partir do DTO (o Mapper ou Connector deve garantir transmit(true))
            com.ib.client.Order order = ibkrMapper.toWhatIfOrder(whatIfOrderId, request.getSide(), request.getQuantity());

            // 1. Executa a simulação REAL
            OrderStateDTO resultState = ibkrConnector.sendWhatIfRequest(contract, order);

            // 2. Mapeamento do valor REAL da Mudança de Margem
            BigDecimal marginChange = ibkrMapper.parseMarginValue(resultState.getInitMarginChange());

            // 🚨 NOVO (Regra [2025-11-03]): Obtém o Buying Power mais fresco
            // O portfolioService gerencia o cache e tem o valor mais atualizado da Ponte.
            realTimeBuyingPower = portfolioService.getFullLiquidityStatus().getCurrentBuyingPower();

            // Logs explicativos para acompanhamento
            log.info("📢 [CONTROLLER | SUCESSO] What-If concluído. Mudança de Margem (R$ {}), Liquidez Atual (R$ {}).",
                    marginChange, realTimeBuyingPower);

            return ResponseEntity.ok(new WhatIfResponseDTO(
                    true,
                    marginChange,
                    realTimeBuyingPower,
                    null
            ));

        } catch (UnsupportedOperationException e) {
            log.error("🛑 [CONTROLLER | What-If REJEITADO] Fluxo de What-If indisponível ou falha interna: {}", e.getMessage());
            // Tenta obter o BP para inclusão na resposta
            try {
                realTimeBuyingPower = portfolioService.getFullLiquidityStatus().getCurrentBuyingPower();
            } catch (Exception ignored) { /* Ignora se falhar */ }

            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new WhatIfResponseDTO(
                    false,
                    BigDecimal.ZERO,
                    realTimeBuyingPower,
                    "Serviço de What-If inativo ou falha de dependência."
            ));
        }
        catch (RuntimeException e) {
            // Captura falha do .join() ou TWS Error (e.g., Code 103, 321)

            // Tenta obter o BP para a resposta de erro (Regra [2025-11-03])
            try {
                realTimeBuyingPower = portfolioService.getFullLiquidityStatus().getCurrentBuyingPower();
            } catch (Exception ignored) {
                log.warn("⚠️ [CONTROLLER | What-If] Falha ao obter Buying Power no catch. Retornando ZERO.");
            }

            log.error("🛑 [CONTROLLER | What-If REJEITADO] Falha ao processar simulação What-If para {}. Motivo: {}",
                    request.getSymbol(), e.getMessage());

            // Retorna o Buying Power conhecido, mesmo que a simulação falhe.
            return ResponseEntity.ok(new WhatIfResponseDTO(
                    false,
                    BigDecimal.ZERO,
                    realTimeBuyingPower,
                    "Simulação de margem falhou: " + e.getMessage()
            ));
        }
    }
}