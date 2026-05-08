package com.example.homegaibkrponte.service;

import com.example.homegaibkrponte.connector.IBKRConnector;
import com.example.homegaibkrponte.dto.OrderDTO;
import com.example.homegaibkrponte.factory.ContractFactory;
import com.example.homegaibkrponte.factory.OrderFactory;
import com.example.homegaibkrponte.model.OrderTypeEnum;
import com.example.homegaibkrponte.monitoring.LivePortfolioService;
import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.Order;
import com.ib.client.Types;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 🛠️ SERVIÇO DE ORDENS CONSOLIDADO (PRONTO PARA USO)
 * Resolvendo Erro 103 (Duplicate ID) e Veto de Liquidez Negativa.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {

    private final IBKRConnector connector;
    private final OrderIdManager orderIdManager;
    private final ContractFactory contractFactory;
    private final OrderFactory orderFactory;
    private final LivePortfolioService portfolioService;
    private final WebhookNotifierService webhookNotifier;

    /**
     * Ponto de entrada para submissão de ordens.
     * Ajustado para permitir que Vendas/Reduções curem a conta DUN652604.
     */
    public OrderDTO placeOrder(OrderDTO orderDto) {
        if (!connector.isConnected()) {
            log.warn("⚠️ [ORDER-SERVICE] Gateway desconectado. Abortando {}.", orderDto.clientOrderId());
            throw new IllegalStateException("Gateway desconectado.");
        }

        OrderTypeEnum typeEnum = orderDto.getTypeAsEnum();
        if (typeEnum == null) {
            log.error("❌ Tipo de ordem não reconhecido: {}", orderDto.type());
            throw new IllegalArgumentException("Tipo de ordem inválido.");
        }

        // ✅ INTELIGÊNCIA: Identifica se é uma ordem de mitigação/redução
        boolean isReductionOrder = typeEnum.getSide().equalsIgnoreCase("SELL") ||
                typeEnum.name().contains("COVER") ||
                (orderDto.rationale() != null && orderDto.rationale().contains("DELEVERAGING"));

        // 🛡️ VETO DE COMPRA: Só veta se for COMPRA e EL negativo. Reduções passam sempre.
        if (!isReductionOrder && portfolioService.getExcessLiquidity().signum() <= 0) {
            BigDecimal el = portfolioService.getExcessLiquidity();
            log.error("❌ [VETO COMPRA] EL Negativo (R$ {}). Bloqueando nova entrada.", el.toPlainString());
            throw new IllegalStateException("Saldo insuficiente para compras. Modo recuperação ativo.");
        }

        log.info("⚙️ [ORDER-SERVICE] Processando {}: {} para {}.",
                isReductionOrder ? "REDUÇÃO" : "COMPRA", typeEnum, orderDto.symbol());

        try {
            if (orderDto.isBracketOrder()) {
                return handleBracketOrder(orderDto);
            }
            return handleSimpleOrder(orderDto, isReductionOrder); // Passa o flag de redução
        } catch (Exception e) {
            log.error("💥 [ORDER-SERVICE] Erro crítico ao submeter {}: {}", orderDto.clientOrderId(), e.getMessage());
            throw new RuntimeException("Falha na Ponte: " + e.getMessage(), e);
        }
    }

    // --- LÓGICA SIMPLES (PREVENÇÃO DE ERRO 103) ---

    // 1. Adicione a dependência no topo da classe
    private final OrderLockManager orderLockManager; // Certifique-se de injetar via construtor

    /**
     * 🛠️ HANDLE SIMPLE ORDER V3.0 - SINERGIA HÍBRIDA & BYPASS MKT
     * Sinergia: Confia no preço do Principal, mas aceita "cegueira" para ordens MARKET.
     * Proteção: Blindagem contra Erro 103 (Duplicate ID) e Double-Ejection.
     */
    private OrderDTO handleSimpleOrder(OrderDTO orderDto, boolean isReduction) {
        String symbol = orderDto.symbol().toUpperCase();
        OrderTypeEnum typeEnum = orderDto.getTypeAsEnum();

        // 🛡️ 1. PROTEÇÃO DE LOCK (Prevenção de Spam de Ejeção)
        if (isReduction && orderLockManager.isLocked(symbol)) {
            log.warn("🚦 [ORDER-LOCK-VETO] Ejeção duplicada evitada para {}. Já existe uma missão em curso.", symbol);
            throw new IllegalStateException("Ordem de ejeção em processamento para " + symbol);
        }

        Contract contract = contractFactory.create(orderDto.symbol());

        // 🎯 2. LÓGICA DE SOBERANIA DE PREÇO
        // Tenta usar limitPrice ou price enviados pelo Principal (Soberania do DTO)
        BigDecimal executionPrice = orderDto.limitPrice() != null ? orderDto.limitPrice() : orderDto.price();

        // 🔓 3. PROTOCOLO DE BYPASS PARA ORDENS MARKET
        boolean isMarket = (typeEnum != null && typeEnum.isMarketOrder());

        if (!isMarket) {
            // Se NÃO for Market, o preço é MANDATÓRIO
            if (executionPrice == null || executionPrice.signum() <= 0) {
                log.warn("⚠️ [DTO-PRICE-MISSING] Ordem LIMIT para {} sem preço. Consultando cache local...", symbol);
                executionPrice = portfolioService.getPriceForOrder(symbol);
            }

            if (executionPrice == null || executionPrice.compareTo(BigDecimal.ZERO) <= 0) {
                log.error("🛑 [CRITICAL-PRICE-MISSING] Falha total de precificação LIMIT para {}. Abortando.", symbol);
                throw new IllegalStateException("Cegueira de preço detectada na Ponte para " + symbol);
            }
        } else {
            // Se FOR Market, o preço é apenas ilustrativo para o rastreio (tracking)
            if (executionPrice == null || executionPrice.signum() <= 0) {
                executionPrice = BigDecimal.ZERO;
                log.warn("🔓 [MARKET-BYPASS] Ejeção a mercado de {} autorizada sem preço de referência.", symbol);
            }
        }

        // 🔄 4. SINCRONIZAÇÃO DO DTO FINAL
        OrderDTO finalDto = (orderDto.price() == null || orderDto.price().signum() <= 0)
                ? orderDto.withPrice(executionPrice) : orderDto;

        // 🚀 5. PREPARAÇÃO E TRANSMISSÃO TWS
        try {
            int finalOrderId = orderIdManager.getNextOrderId();
            Order ibkrOrder = orderFactory.create(finalDto, String.valueOf(finalOrderId));

            // Vincula IDs para o rádio (Callback)
            orderIdManager.linkIds(finalDto.clientOrderId(), finalOrderId);

            log.info("📦 [TWS-OUT] Despachando {} | Qtd: {} | Lado/Modo: {} | Ref: ${}",
                    finalDto.symbol(), ibkrOrder.totalQuantity().value(), typeEnum, executionPrice);

            // Rastreio interno para ajuste de Buying Power local
            portfolioService.trackOrderSent(
                    finalDto.clientOrderId(),
                    finalDto.symbol(),
                    BigDecimal.valueOf(ibkrOrder.totalQuantity().value().doubleValue()),
                    executionPrice
            );

            // Disparo físico para o Gateway
            connector.placeOrder(String.valueOf(finalOrderId), contract, ibkrOrder);

            // Trava o ativo se for redução para evitar ordens sobrepostas
            if (isReduction) {
                orderLockManager.lock(symbol);
            }

            return finalDto.withOrderId(finalOrderId);

        } catch (Exception e) {
            log.error("💥 [FATAL-ORDER-FLOW] Falha no despacho de {}: {}", finalDto.symbol(), e.getMessage());
            throw new RuntimeException("Falha no fluxo de ordem da Ponte: " + e.getMessage(), e);
        }
    }

    private OrderDTO handleBracketOrder(OrderDTO masterOrderDto) {
        Contract contract = contractFactory.create(masterOrderDto.symbol());
        int masterId = orderIdManager.getNextOrderId();
        int slId = orderIdManager.getNextOrderId();
        int tpId = orderIdManager.getNextOrderId();

        Order parentOrder = orderFactory.create(masterOrderDto, String.valueOf(masterId));
        OrderDTO slDto = masterOrderDto.childOrders().stream().filter(OrderDTO::isStopLoss).findFirst().get();
        OrderDTO tpDto = masterOrderDto.childOrders().stream().filter(OrderDTO::isTakeProfit).findFirst().get();

        Order slOrder = orderFactory.create(slDto, String.valueOf(slId));
        Order tpOrder = orderFactory.create(tpDto, String.valueOf(tpId));

        parentOrder.transmit(false);
        slOrder.parentId(masterId);
        tpOrder.parentId(masterId);
        slOrder.transmit(false);
        tpOrder.transmit(true);

        connector.placeOrder(String.valueOf(masterId), contract, parentOrder);
        connector.placeOrder(String.valueOf(slId), contract, slOrder);
        connector.placeOrder(String.valueOf(tpId), contract, tpOrder);

        return masterOrderDto.withOrderId(masterId)
                .withChildOrders(List.of(slDto.withOrderId(slId), tpDto.withOrderId(tpId)));
    }

    /**
     * 🧹 [SINERGIA DE CANCELAMENTO] Traduz e envia o comando de cancelamento para a TWS.
     * Crucial para liberar o Buying Power (Margem) após falha na Auto-Cura no Principal.
     */
    public void cancelOrder(String clientOrderId) {
        try {
            if (clientOrderId == null || clientOrderId.isBlank()) return;

            log.warn("🧹 [ORDER-SERVICE] Iniciando protocolo de cancelamento para ClientID: {}", clientOrderId);

            // 1. Recupera o ID numérico vinculado no momento do placeOrder
            Integer ibkrOrderId = orderIdManager.getIbkrOrderId(clientOrderId);

            if (ibkrOrderId != null) {
                // 2. Comando Real conforme a assinatura do EClient
                // Passamos um objeto OrderCancel vazio para compatibilidade com versões modernas
                com.ib.client.OrderCancel cancelRequest = new com.ib.client.OrderCancel();

                connector.getClient().cancelOrder(ibkrOrderId, cancelRequest);

                log.info("✅ [ORDER-SERVICE] Comando cancelOrder enviado para TWS (IBKR ID: {}).", ibkrOrderId);

                // 3. Notifica o Principal via alerta de liquidez (Recuperação de Margem)
                webhookNotifier.notifyWarningLiquidity("Ordem " + clientOrderId + " cancelada na Ponte para liberar margem.");

                // 4. Limpa o mapeamento para poupar memória
                orderIdManager.removeMapping(clientOrderId);
            } else {
                log.error("❌ [ORDER-SERVICE] Cancelamento abortado: ClientID {} não mapeado para um ID IBKR.", clientOrderId);
            }

        } catch (Exception e) {
            log.error("💥 [ORDER-SERVICE] Erro crítico ao cancelar ordem {}: {}", clientOrderId, e.getMessage());
            throw new RuntimeException("Falha ao cancelar na Ponte: " + e.getMessage());
        }
    }

}