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
import java.util.ArrayList;
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

    @GetMapping("/buying-power")
    public ResponseEntity<BigDecimal> getBuyingPower() {
        log.info("------------------------------------------------------------");
        log.info("💰 Requisição REST recebida para '/buying-power'.");

        if (!connector.isConnected()) {
            log.error("❌ Abortando: Conexão com a corretora não está ativa.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(BigDecimal.ZERO);
        }

        try {
            // Se o sistema já foi sincronizado uma vez, retorna o valor atual rapidamente.
            if (portfolioService.isSynced()) {
                BigDecimal currentBuyingPower = portfolioService.getCurrentBuyingPower();
                log.info("✔️ Sistema já sincronizado. Retornando Poder de Compra em cache: R$ {}", currentBuyingPower);
                return ResponseEntity.ok(currentBuyingPower);
            }

            // Se for a primeira sincronização, usa o mecanismo de espera.
            log.warn("⏳ Sistema ainda não sincronizado. Disparando 'reqAccountUpdates' e aguardando...");
            connector.getClient().reqAccountUpdates(true, "All"); // Inicia a subscrição de dados da conta

            // Aguarda o sinal do LivePortfolioService
            boolean syncCompleted = portfolioService.awaitInitialSync(15000); // Timeout de 15 segundos

            // Cancela a subscrição para não consumir recursos desnecessariamente
            connector.getClient().reqAccountUpdates(false, "All");

            if (!syncCompleted) {
                log.error("❌ TIMEOUT! A sincronização de saldo não ocorreu em 15 segundos.");
                return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(BigDecimal.ZERO);
            }

            log.info("✔️ Sincronização de saldo confirmada.");
            BigDecimal currentBuyingPower = portfolioService.getCurrentBuyingPower();
            log.info("💸 Retornando o Poder de Compra sincronizado: R$ {}", currentBuyingPower);
            return ResponseEntity.ok(currentBuyingPower);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ A thread foi interrompida enquanto esperava pela sincronização de saldo.", e);
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
            // Reinicia o "sinalizador" para garantir que vamos esperar pela NOVA resposta
            portfolioService.resetPositionSyncLatch();

            log.info("➡️  Solicitando posições à corretora e aguardando resposta...");
            connector.getClient().reqPositions();

            // Pausa a execução aqui e espera o LivePortfolioService avisar que terminou
            boolean syncCompleted = portfolioService.awaitPositionSync(60000); // Timeout de 15s

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
        log.info("🛒 [Ponte | Controller] Recebida requisição REST para executar ordem: {}", orderDto.clientOrderId());
        try {
            OrderDTO resultDto = orderService.placeOrder(orderDto);
            log.info("✅ [Ponte | Controller] Ordem {} processada com sucesso. DTO com ID IBKR: {}", resultDto.clientOrderId(), resultDto.orderId());
            return ResponseEntity.ok(resultDto);
        } catch (IllegalStateException e) {
            log.error("🚫 [Ponte | Controller] Ordem Rejeitada (BAD_REQUEST): {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(null);
        } catch (Exception e) {
            log.error("💥 [Ponte | Controller] Erro crítico ao processar ordem (INTERNAL_SERVER_ERROR): {}", e.getMessage(), e);
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

