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

    private OrderDTO handleSimpleOrder(OrderDTO orderDto, boolean isReduction) {
        String symbol = orderDto.symbol().toUpperCase();

        // 🛡️ 1. CONSULTA A CUSTÓDIA REAL (A "Verdade do Cofre")
        BigDecimal currentPosition = portfolioService.getPositionForSymbol(symbol);

        // 🛠️ 2. INFERÊNCIA DE LADO (O TRADUTOR INTELIGENTE)
        // Extraímos a intenção original do campo 'type' (ex: de SELL_MARKET, extraímos SELL)
        String originalSide = orderDto.type().split("_")[0].toUpperCase(); // "SELL" ou "BUY"

        // Criamos uma variável local para a ação física real
        String physicalAction = originalSide;

        // Se o ID indica fechamento (CLOSE/EXIT) ou o flag de redução está ativo
        if (orderDto.clientOrderId().contains("CLOSE") || orderDto.clientOrderId().contains("EXIT") || isReduction) {

            // SE estou DEVENDO (SHORT < 0) e o Winston mandou um sinal de VENDA (SELL)
            if (currentPosition.signum() < 0 && originalSide.equals("SELL")) {
                log.warn("🚨 [PONTE-INFERÊNCIA] Fechando SHORT em {}: Invertendo sinal SELL para BUY físico.", symbol);
                physicalAction = "BUY";
            }
            // SE estou COMPRADO (LONG > 0) e recebi sinal de COMPRA (BUY)
            else if (currentPosition.signum() > 0 && originalSide.equals("BUY")) {
                log.warn("🚨 [PONTE-INFERÊNCIA] Fechando LONG em {}: Invertendo sinal BUY para SELL físico.", symbol);
                physicalAction = "SELL";
            }
        }

        // 🚀 3. PREPARAÇÃO DA ORDEM IBKR (Usando a ação física inferida)
        Contract contract = contractFactory.create(orderDto.symbol());

        try {
            int finalOrderId = orderIdManager.getNextOrderId();

            // Criamos a ordem física.
            // IMPORTANTE: Aqui precisamos garantir que a OrderFactory use o physicalAction
            Order ibkrOrder = orderFactory.create(orderDto, String.valueOf(finalOrderId));
            ibkrOrder.action(physicalAction); // <== SOBRESCREVEMOS O LADO AQUI

            orderIdManager.linkIds(orderDto.clientOrderId(), finalOrderId);

            log.info("📦 [TWS-OUT] Despachando {} | Qtd: {} | Lado Físico: {} | Ref: {}",
                    symbol, ibkrOrder.totalQuantity().value(), physicalAction, orderDto.clientOrderId());

            portfolioService.trackOrderSent(
                    orderDto.clientOrderId(),
                    symbol,
                    BigDecimal.valueOf(ibkrOrder.totalQuantity().value().doubleValue()),
                    orderDto.limitPrice() != null ? orderDto.limitPrice() : BigDecimal.ZERO
            );

            // Disparo com o ID de texto original para manter os logs de auditoria
            connector.placeOrder(orderDto.clientOrderId(), contract, ibkrOrder);

            return orderDto.withOrderId(finalOrderId);

        } catch (Exception e) {
            log.error("💥 [ORDER-SERVICE] Falha: {}", e.getMessage());
            throw new RuntimeException(e);
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