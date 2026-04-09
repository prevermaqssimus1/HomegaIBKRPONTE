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

    private OrderDTO handleSimpleOrder(OrderDTO orderDto, boolean isReduction) {
        int tempId = orderIdManager.getNextOrderId();
        Contract contract = contractFactory.create(orderDto.symbol());
        BigDecimal executionPrice = portfolioService.getPriceForOrder(orderDto.symbol());

        // 🛡️ SINERGIA: Garante que ordens com a tag de urgência sejam tratadas como redução de risco
        boolean isUrgente = isReduction ||
                (orderDto.rationale() != null && orderDto.rationale().contains("[URGENT-CLOSE]"));

        OrderDTO finalDto = orderDto;
        if (orderDto.price() == null || orderDto.price().signum() <= 0) {
            finalDto = orderDto.withPrice(executionPrice);
        }

        Order ibkrOrder = orderFactory.create(finalDto, String.valueOf(tempId));

        // ✅ PRIORIDADE ABSOLUTA: Se for fechamento, ignoramos restrições de preço da corretora
        if (isUrgente) {
            ibkrOrder.overridePercentageConstraints(true);
            log.warn("🛡️ [RECOVERY-MODE] Ativando Liquidação de Emergência para {}. Ignorando travas de preço.", finalDto.symbol());
        }

        try {
            log.info("🔍 [PRE-CHECK] Simulando margem para {} (ID: {})", finalDto.symbol(), tempId);
            boolean temMargem = connector.validarMargemPreventiva(contract, ibkrOrder);

            int finalOrderId = orderIdManager.getNextOrderId();
            ibkrOrder.orderId(finalOrderId);
            orderIdManager.linkIds(finalDto.clientOrderId(), finalOrderId);

            if (!temMargem) {
                double qtdOriginal = ibkrOrder.totalQuantity().value().doubleValue();

                // 🔥 EFEITO CUNHA: Se for urgente (fechar short), tentamos apenas 10%.
                // Se for abertura comum, tentamos 60%.
                double fatorReducao = isUrgente ? 0.10 : 0.60;
                double novaQtd = Math.max(1.0, Math.floor(qtdOriginal * fatorReducao));

                String elProjetado = connector.getLastWhatIfExcessLiquidity();

                log.warn("📉 [MARGIN-FATAL] Margem insuficiente para {}. Aplicando FRACIONAMENTO: {} -> {} | Motivo: {}",
                        finalDto.symbol(), qtdOriginal, novaQtd, isUrgente ? "LIQUIDAÇÃO_URGENTE" : "ADAPTIVE_ENTRY");

                ibkrOrder.totalQuantity(Decimal.get(novaQtd));
                webhookNotifier.sendAdaptiveCheckAlert(finalDto.symbol(), qtdOriginal, novaQtd, "Recuperação de Margem 201");
            }

            BigDecimal custoReal = BigDecimal.valueOf(ibkrOrder.totalQuantity().value().doubleValue())
                    .multiply(executionPrice).abs();

            log.info("📦 [TWS-OUT] Despachando {} | Qtd Final: {} | Preço: ${} | Custo: ${}",
                    finalDto.symbol(), ibkrOrder.totalQuantity().value(), executionPrice, custoReal);

            portfolioService.trackOrderSent(finalDto.clientOrderId(), finalDto.symbol(),
                    BigDecimal.valueOf(ibkrOrder.totalQuantity().value().doubleValue()), executionPrice);

            connector.placeOrder(finalOrderId, contract, ibkrOrder);
            return finalDto.withOrderId(finalOrderId);

        } catch (Exception e) {
            log.error("💥 [FATAL-ORDER-FLOW] Erro ao processar {}: {}", finalDto.symbol(), e.getMessage());
            throw new RuntimeException("Falha no fluxo de ordem da Ponte", e);
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

        connector.placeOrder(masterId, contract, parentOrder);
        connector.placeOrder(slId, contract, slOrder);
        connector.placeOrder(tpId, contract, tpOrder);

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