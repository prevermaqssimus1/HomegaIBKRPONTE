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

        // 1. 🛡️ RECUPERAÇÃO DO ENUM (Para identificar Lado sem campo 'side' no Record)
        OrderTypeEnum typeEnum = orderDto.getTypeAsEnum();
        if (typeEnum == null) {
            log.error("❌ Tipo de ordem não reconhecido: {}", orderDto.type());
            throw new IllegalArgumentException("Tipo de ordem inválido.");
        }

        // 2. 🚨 VETO DE LIQUIDEZ INTELIGENTE (Libera SELL mesmo com EL negativo)
        // Identifica se a ordem é SELL ou DELEVERAGING para ignorar saldo negativo de R$ -1.192,81
        boolean isReductionOrder = typeEnum.getSide().equalsIgnoreCase("SELL") ||
                typeEnum.name().contains("COVER") ||
                (orderDto.rationale() != null && orderDto.rationale().contains("DELEVERAGING"));

        // 🛡️ SÓ VETA SE: For uma COMPRA e o Excess Liquidity for <= 0
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
            return handleSimpleOrder(orderDto);
        } catch (Exception e) {
            log.error("💥 [ORDER-SERVICE] Erro crítico ao submeter {}: {}", orderDto.clientOrderId(), e.getMessage());
            throw new RuntimeException("Falha na Ponte: " + e.getMessage(), e);
        }
    }

    // --- LÓGICA SIMPLES (PREVENÇÃO DE ERRO 103) ---

    private OrderDTO handleSimpleOrder(OrderDTO orderDto) {
        // 1. Gera o ID para a simulação What-If
        int simId = orderIdManager.getNextOrderId();
        Contract contract = contractFactory.create(orderDto.symbol());
        Order ibkrOrder = orderFactory.create(orderDto, String.valueOf(simId));

        try {
            log.info("🔍 [PRE-CHECK] Simulando margem para {} (ID: {})", orderDto.symbol(), simId);

            // 🔍 Simulação What-If antes do envio real
            boolean temMargem = connector.validarMargemPreventiva(contract, ibkrOrder);

            // ✅ AJUSTE DEFINITIVO PARA ERRO 103:
            // Gerar SEMPRE um novo ID para a ordem real após a simulação.
            // Isso garante que o ID da simulação nunca conflite com o envio real.
            int finalOrderId = orderIdManager.getNextOrderId();
            ibkrOrder.orderId(finalOrderId);

            if (!temMargem) {
                double qtdOriginal = ibkrOrder.totalQuantity().value().doubleValue();
                double novaQtd = Math.floor(qtdOriginal * 0.60);
                String elProjetado = connector.getLastWhatIfExcessLiquidity();

                log.warn("📉 [ADAPTIVE-SIZE] Margem insuficiente. Reduzindo lote: {} -> {} | EL: {}",
                        qtdOriginal, novaQtd, elProjetado);

                ibkrOrder.totalQuantity(Decimal.get(novaQtd));

                try {
                    webhookNotifier.sendAdaptiveCheckAlert(orderDto.symbol(), qtdOriginal, novaQtd, elProjetado);
                } catch (Exception ex) {
                    log.error("⚠️ [WEBHOOK] Falha na notificação: {}", ex.getMessage());
                }
            } else {
                log.info("🚀 [MARGIN-OK] Ordem mantida em {}. Procedendo com ID: {}",
                        ibkrOrder.totalQuantity(), finalOrderId);
            }

            // Envio final para o Conector
            connector.placeOrder(finalOrderId, contract, ibkrOrder);

            return orderDto.withOrderId(finalOrderId);
        } catch (Exception e) {
            log.error("💥 [FATAL] Erro no fluxo preventivo para {}: {}", orderDto.symbol(), e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private OrderDTO handleBracketOrder(OrderDTO masterOrderDto) {
        Contract contract = contractFactory.create(masterOrderDto.symbol());

        // Geração de IDs únicos para a estrutura Bracket
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

        log.info("🚀 [BRACKET] Enviando estrutura. Master ID: {}", masterId);

        connector.placeOrder(masterId, contract, parentOrder);
        connector.placeOrder(slId, contract, slOrder);
        connector.placeOrder(tpId, contract, tpOrder);

        return masterOrderDto.withOrderId(masterId)
                .withChildOrders(List.of(slDto.withOrderId(slId), tpDto.withOrderId(tpId)));
    }
}