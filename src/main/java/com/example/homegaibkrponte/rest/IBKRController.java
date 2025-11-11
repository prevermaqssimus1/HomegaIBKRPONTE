// File: src/main/java/com/example/homegaibkrponte/rest/IBKRController.java (Atualizado)
package com.example.homegaibkrponte.rest;

import com.example.homegaibkrponte.connector.IBKRConnector;
import com.example.homegaibkrponte.dto.OrderDTO;
import com.example.homegaibkrponte.dto.MarginWhatIfResponseDTO; // Importação do novo DTO
import com.example.homegaibkrponte.model.Position;
import com.example.homegaibkrponte.model.PositionDTO;
import com.example.homegaibkrponte.monitoring.LivePortfolioService;
import com.example.homegaibkrponte.service.OrderIdManager;
import com.example.homegaibkrponte.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Controlador REST principal para interações com a ponte IBKR.
 * Centraliza todos os endpoints para status, ordens e informações da conta.
 */
@RestController
@RequestMapping("/api/ibkr")
@RequiredArgsConstructor
@Slf4j
public class IBKRController {

    private final IBKRConnector connector;
    private final OrderService orderService;
    private final LivePortfolioService portfolioService;
    private final OrderIdManager orderIdManager;

    @GetMapping("/status")
    public ResponseEntity<String> getStatus() {
        if (!connector.isConnected()) {
            connector.connect();
        }
        return connector.isConnected()
                ? ResponseEntity.ok("CONNECTED")
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("DISCONNECTED");
    }

    /**
     * 🚨 NOVO ENDPOINT (SINERGIA): Força a sincronização completa dos valores de conta (BP, EL, NLV) do TWS.
     * Necessário para resolver o problema de dados de margem desatualizados no Principal.
     */
    @GetMapping("/sync-account-values")
    public ResponseEntity<Void> syncAccountValues(@RequestParam String accountId) {
        if (!connector.isConnected()) {
            log.error("❌ [Ponte | SYNC] Conexão com TWS inativa. Não é possível sincronizar valores de conta.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        log.info("➡️ [Ponte | SYNC] Recebida requisição do Principal para sincronização forçada de valores de conta para {}", accountId);

        try {
            // Dispara a subscrição de atualização de conta no TWS (que é assíncrona)
            // Usamos reqAccountUpdates que dispara o callback updateAccountValue
            connector.getClient().reqAccountUpdates(true, accountId);
            log.warn("🔄 [Ponte | SYNC] Subscrição de Account Updates enviada ao TWS. Dados serão atualizados via callback.");

            // Retornamos OK imediatamente, pois a atualização é assíncrona.
            return ResponseEntity.ok().build();

        } catch (Exception e) {
            log.error("❌ [Ponte | SYNC] Falha ao iniciar a sincronização de valores de conta no TWS.", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * ✅ CORRIGIDO: Este método agora busca o poder de compra em tempo real a cada requisição.
     */
    @GetMapping("/buying-power")
    public ResponseEntity<BigDecimal> getBuyingPower() {
        log.info("------------------------------------------------------------");
        log.info("💰 Requisição REST recebida para '/buying-power' (em tempo real).");

        if (!connector.isConnected()) {
            log.error("❌ Abortando: Conexão com a corretora não está ativa. Retornando ZERO.");
            return ResponseEntity.ok(BigDecimal.ZERO);
        }

        // PASSO 1: Captura o estado atual do Buying Power antes da requisição TWS
        LivePortfolioService.AccountBalance initialSnapshot = portfolioService.getLastBuyingPowerSnapshot();
        BigDecimal cachedBuyingPower = initialSnapshot.value();

        try {
            portfolioService.resetAccountSyncLatch();

            log.warn("⏳ Disparando 'reqAccountUpdates' e aguardando atualização de saldo em tempo real...");
            connector.getClient().reqAccountUpdates(true, "All");

            boolean syncCompleted = portfolioService.awaitInitialSync(15000);

            connector.getClient().reqAccountUpdates(false, "All");

            if (!syncCompleted) {
                // AJUSTE CRÍTICO: TIMEOUT. Usar o valor MAIS FRESCO disponível.
                BigDecimal finalBuyingPower = portfolioService.getCurrentBuyingPower();
                BigDecimal fallbackValue = (finalBuyingPower.compareTo(BigDecimal.ZERO) == 0 && cachedBuyingPower.compareTo(BigDecimal.ZERO) == 0)
                        ? BigDecimal.ZERO : finalBuyingPower;

                log.error("❌ TIMEOUT (15s)! Sincronização falhou. Retornando valor de FALLBACK (R${}).", fallbackValue);
                return ResponseEntity.ok(fallbackValue);
            }

            // Se SUCESSO, retorna o valor atualizado.
            BigDecimal currentBuyingPower = portfolioService.getCurrentBuyingPower();
            log.info("💸 Retornando o Poder de Compra sincronizado em tempo real: R$ {}", currentBuyingPower);
            return ResponseEntity.ok(currentBuyingPower);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ A thread foi interrompida enquanto esperava pela sincronização de saldo. Retornando ZERO.", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(BigDecimal.ZERO);
        } finally {
            log.info("------------------------------------------------------------");
        }
    }

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

            boolean syncCompleted = portfolioService.awaitPositionSync(60000);

            if (!syncCompleted) {
                log.error("❌ TIMEOUT! A sincronização de posições não ocorreu em 60 segundos.");
                return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Collections.emptyList());
            }

            // Quando a espera termina, o portfólio já está atualizado. Agora podemos ler.
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
     * Resolve o problema do Resgate e a discrepância de Liquidez.
     */
    @PostMapping("/sync-snapshot")
    public ResponseEntity<Void> triggerAccountSummarySnapshot() {
        // Aplica try-catch e logs explicativos (Obrigatório)
        try {
            log.info("🔄 [Ponte | SYNC COMANDO] Recebido comando do Principal para forçar o Account Summary Snapshot.");

            // ✅ CORREÇÃO: Chama o método existente no IBKRConnector
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

    @PostMapping("/place-order")
    public ResponseEntity<OrderDTO> placeOrder(@RequestBody OrderDTO orderDto) {
        // Log de Entrada - Indica o início do processamento da ordem na Ponte
        log.info("🛒 [Ponte | Controller] Recebida requisição REST para executar ordem. ClientID: {}", orderDto.clientOrderId());

        try {
            // Chamada ao serviço principal para submeter ao TWS
            OrderDTO resultDto = orderService.placeOrder(orderDto);

            // Log de Saída - Indica que a ordem foi submetida com sucesso ao TWS/Gateway
            log.info("🚀 [Ponte | Controller] Ordem SUBMETIDA. ClientID: {}, ID IBKR: {}. Aguardando callbacks de status.",
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

    @GetMapping("/margin/nlv")
    public ResponseEntity<BigDecimal> getNetLiquidationValue() {
        // Aplica try-catch e logs explicativos (Obrigatório)
        try {
            // Assume-se que o NLV do dia anterior pode ser espelhado pelo NLV mais fresco,
            // que é armazenado no cache da Ponte durante a sincronização.
            BigDecimal netLiquidation = portfolioService.getNetLiquidationValue();

            if (netLiquidation.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("⚠️ [Ponte | NLV] Net Liquidation Value (NLV) retornou R$ 0.00. Assumindo que o dado é do dia anterior, mas está indisponível/zerado. Veto de Sizing possível no Principal.");
            }

            log.info("✅ [Ponte | NLV] Retornando Net Liquidation Value (PL) para o Principal: R$ {}", netLiquidation);
            return ResponseEntity.ok(netLiquidation);

        } catch (Exception e) {
            log.error("❌ [Ponte | ERRO NLV] Falha crítica ao obter Net Liquidation Value (PL). Forçando R$ 0.00. Rastreando.", e);
            // Retorna ZERO, forçando o veto no Sizing do Principal (Fail-safe, mais seguro que um valor incorreto).
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(BigDecimal.ZERO);
        }
    }




    @PostMapping("/margin/what-if")
    public ResponseEntity<MarginWhatIfResponseDTO> requestMarginWhatIf(@RequestBody MarginWhatIfRequest request) {
        log.info("➡️ [Ponte | Controller] Recebida requisição REST de What-If para {} (Qty: {}).", request.symbol(), request.quantity());

        // Conversão obrigatória para o DTO de resposta (alinha com a sinergia de tipos)
        BigDecimal requestQuantityBd = new BigDecimal(request.quantity());

        if (!connector.isConnected()) {
            log.error("❌ [Ponte | What-If] Conexão com TWS inativa. Retornando erro de serviço indisponível.");
            // Linha 285 (Exemplo): Construtor DTO com 7 argumentos e BigDecimal
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    new MarginWhatIfResponseDTO(
                            request.symbol(),
                            requestQuantityBd, // ✅ Tipo BigDecimal
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            BigDecimal.ZERO, // commissionEstimate
                            null,            // currency
                            "Conexão com a corretora indisponível."
                    )
            );
        }

        try {
            // ✅ CHAMADA À PONTE (Bridge)
            // A assinatura do request.quantity() aqui deve ser compatível com o connector.requestMarginWhatIf(String, int)
            // Se o request.quantity() for int, passe-o; se for BigDecimal, ajuste o método do connector.
            MarginWhatIfResponseDTO response = connector.requestMarginWhatIf(request.symbol(), request.quantity());

            // Log de retorno
            if (response.error() != null && !response.error().isEmpty()) {
                log.warn("⚠️ [Ponte | What-If] Simulação What-If para {} retornou erro do TWS: {}", request.symbol(), response.error());
            } else {
                log.info("✅ [Ponte | What-If] Simulação What-If para {} retornou sucesso. Mudança Margem Inicial: R$ {}",
                        request.symbol(), response.initialMarginChange());
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("❌ [Ponte | ERRO What-If] Falha CRÍTICA ao processar What-If para {}. Rastreando.", request.symbol(), e);
            // Linha 322 (Exemplo): Construtor DTO com 7 argumentos e BigDecimal
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    new MarginWhatIfResponseDTO(
                            request.symbol(),
                            requestQuantityBd, // ✅ Tipo BigDecimal
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            BigDecimal.ZERO, // commissionEstimate
                            null,            // currency
                            "Erro interno da Ponte ao executar What-If: " + e.getMessage()
                    )
            );
        }
    }




    // Classe interna para mapear a requisição de What-If (Obrigatório: Records para DTOs)
    private record MarginWhatIfRequest(String symbol, int quantity) {}
}