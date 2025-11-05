package com.example.homegaibkrponte.rest;

import com.example.homegaibkrponte.connector.IBKRConnector;
import com.example.homegaibkrponte.dto.OrderDTO;
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
     * ✅ CORRIGIDO: Este método agora busca o poder de compra em tempo real a cada requisição.
     * A lógica de cache foi removida para garantir que o valor retornado seja sempre o mais atual.
     */
    // MÉTODO PRONTO PARA SUBSTITUIR: IBKRController.getBuyingPower()

    // MÉTODO PRONTO PARA SUBSTITUIR: IBKRController.getBuyingPower()

    // MÉTODO PRONTO PARA SUBSTITUIR: IBKRController.getBuyingPower()

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

                // Re-captura o valor, caso tenha chegado no TWS no último milissegundo do timeout.
                BigDecimal finalBuyingPower = portfolioService.getCurrentBuyingPower();

                // Se o valor FINAL ainda for ZERO (e deu timeout), ativa a emergência.
                BigDecimal fallbackValue = (finalBuyingPower.compareTo(BigDecimal.ZERO) == 0 && cachedBuyingPower.compareTo(BigDecimal.ZERO) == 0)
                        ? BigDecimal.ZERO : finalBuyingPower;

                // ✅ MELHORIA DE LOG: Indica que o fallback foi usado
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
// MANTENHA TODOS OS OUTROS MÉTODOS DO IBKRController IGUAIS (getPositions, placeOrder, etc.)
// A CLASSE COMPLETA NÃO FOI REPETIDA AQUI PARA MANTER A CLAREZA, MAS VOCÊ DEVE SUBSTITUIR APENAS ESTE MÉTODO.

    @GetMapping("/positions")
    public ResponseEntity<List<PositionDTO>> getOpenPositions() {
        log.info("------------------------------------------------------------");
        log.info("📊 Requisição REST recebida para '/positions'.");

        if (!connector.isConnected()) {
            log.error("❌ Abortando: Conexão com a corretora não está ativa.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyList());
        }

        try {
            // Reinicia o "sinalizador" para garantir que vamos esperar pela NOVA resposta
            portfolioService.resetPositionSyncLatch();

            log.info("➡️  Solicitando posições à corretora e aguardando resposta...");
            connector.getClient().reqPositions();

            // Pausa a execução aqui e espera o LivePortfolioService avisar que terminou
            boolean syncCompleted = portfolioService.awaitPositionSync(60000); // Timeout de 60s

            if (!syncCompleted) {
                log.error("❌ TIMEOUT! A sincronização de posições não ocorreu em 60 segundos.");
                return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Collections.emptyList());
            }

            // Quando a espera termina, o portfólio já está atualizado. Agora podemos ler.
            List<PositionDTO> openPositions = portfolioService.getLivePortfolioSnapshot()
                    .openPositions()
                    .values()
                    .stream()
                    .map(this::mapPositionToDTO) // Usa o método auxiliar para conversão
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
            // É crucial usar o log.error com a exceção (e) para que o stack trace seja registrado.
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



}