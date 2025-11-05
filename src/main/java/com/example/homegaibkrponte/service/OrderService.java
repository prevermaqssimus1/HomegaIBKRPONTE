package com.example.homegaibkrponte.service;

import com.example.homegaibkrponte.connector.IBKRConnector;
import com.example.homegaibkrponte.dto.OrderDTO;
import com.example.homegaibkrponte.factory.ContractFactory;
import com.example.homegaibkrponte.factory.OrderFactory;
import com.ib.client.Contract;
import com.ib.client.Order;
import com.ib.client.Types;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {

    private final IBKRConnector connector;
    private final OrderIdManager orderIdManager;
    private final ContractFactory contractFactory;
    private final OrderFactory orderFactory;

    /**
     * Ponto de entrada para receber ordens via REST.
     */
    public OrderDTO placeOrder(OrderDTO orderDto) {
        if (!connector.isConnected()) {
            log.warn("⚠️ [Ponte | ORDER-SERVICE] TWS/Gateway está DESCONECTADO. Não é possível processar a ordem {}.", orderDto.clientOrderId());
            throw new IllegalStateException("Não é possível enviar ordem: Desconectado do TWS/Gateway.");
        }

        // Log de Entrada (SINERGIA: Usando rationale para o 'SINAL' do Domínio Principal)
        // O campo 'rationale' (Justificativa) geralmente carrega a estratégia (ex: LIMIT_STRATEGY_SIGNAL)
        // Usamos orderDto.type() para o Tipo de Ordem IBKR (LMT, MKT)
        log.info("⚙️ [Ponte | ORDER-SERVICE] Recebendo ordem {}. Ativo: {}, SINAL: {}, Tipo IBKR: {}.",
                orderDto.clientOrderId(),
                orderDto.symbol(),
                orderDto.rationale(), // <--- USANDO RATIONALE/SINAL PARA RASTREAMENTO
                orderDto.type());     // <--- USANDO TYPE PARA TIPO DE ORDEM IBKR

        try {
            if (orderDto.isBracketOrder()) {
                log.info("➡️ [Ponte | ORDER-SERVICE] Decisão: BRACKET ORDER (Ordem Mãe + SL/TP). Processando...");
                return handleBracketOrder(orderDto);
            }

            log.info("➡️ [Ponte | ORDER-SERVICE] Decisão: ORDEM SIMPLES ({}) para {}. Processando...",
                    orderDto.type(), // Usando o tipo IBKR para a decisão
                    orderDto.symbol());
            return handleSimpleOrder(orderDto);

        } catch (IllegalStateException e) {
            log.warn("🚫 [Ponte | ORDER-SERVICE] Ordem {} REJEITADA por falha de validação estrutural: {}",
                    orderDto.clientOrderId(), e.getMessage());
            throw e;

        } catch (Exception e) {
            log.error("💥 [Ponte | ORDER-SERVICE] Erro CRÍTICO ao submeter ordem {} de {}. Falha na comunicação ou mapeamento.",
                    orderDto.clientOrderId(), orderDto.symbol(), e);
            throw new RuntimeException("Falha ao processar a ordem na Ponte: " + e.getMessage(), e);
        }
    }
    // --- LÓGICA ATÔMICA BRACKET ORDER (Fase 9) ---

    private OrderDTO handleBracketOrder(OrderDTO masterOrderDto) {
        // ... (Validações de Estrutura: 1, 2) e (Geração de IDs: 3) e (Configuração OCO/Parent: 4) ...
        // (Assumindo que os passos 1 a 4 acima estão no corpo do método)

        // 1. Validação (Apenas da estrutura)
        if (masterOrderDto.childOrders().size() != 2) {
            log.error("❌ [Ponte | VAL-BRACKET] A Ordem Bracket para {} não contém as 2 ordens de proteção (SL/TP).", masterOrderDto.symbol());
            throw new IllegalStateException("Ordem Composta inválida. Esperado 2 ordens filhas, recebido: " + masterOrderDto.childOrders().size());
        }

        // 2. Separação dos DTOs (Uso de get para evitar Optional.get() e forçar a exceção)
        OrderDTO slDto = masterOrderDto.childOrders().stream()
                .filter(OrderDTO::isStopLoss).findFirst()
                .orElseThrow(() -> new IllegalStateException("SL Order faltando."));

        OrderDTO tpDto = masterOrderDto.childOrders().stream()
                .filter(OrderDTO::isTakeProfit).findFirst()
                .orElseThrow(() -> new IllegalStateException("TP Order faltando."));

        // 3. Geração de IDs e Criação dos objetos IBKR
        Contract contract = contractFactory.create(masterOrderDto.symbol());

        int masterOrderId = orderIdManager.getNextOrderId();
        Order parentOrder = orderFactory.create(masterOrderDto, String.valueOf(masterOrderId));

        int slOrderId = orderIdManager.getNextOrderId();
        Order slOrder = orderFactory.create(slDto, String.valueOf(slOrderId));

        int tpOrderId = orderIdManager.getNextOrderId();
        Order tpOrder = orderFactory.create(tpDto, String.valueOf(tpOrderId));

        // 4. Configuração Parent/Child e OCO (IBKR)
        parentOrder.transmit(false);
        slOrder.parentId(masterOrderId);
        tpOrder.parentId(masterOrderId);

        String ocaGroup = Optional.ofNullable(masterOrderDto.clientOrderId()).orElse(String.valueOf(masterOrderId)) + ".oco";
        slOrder.ocaGroup(ocaGroup);
        tpOrder.ocaGroup(ocaGroup);

        slOrder.ocaType(Types.OcaType.CancelWithBlocking);
        tpOrder.ocaType(Types.OcaType.CancelWithBlocking);

        // 5. ENVIO ATÔMICO
        try {
            // Preparação para envio
            parentOrder.transmit(false);
            slOrder.transmit(false);
            tpOrder.transmit(true);

            // Log de ENTRADA DA VENDA/COMPRA (Mãe)
            log.info("🚀 [Ponte | EXEC-BRACKET] Iniciando envio da Mestra ({}) com ID IBKR {}. Ação: {}, Tipo: {}.",
                    masterOrderDto.symbol(), masterOrderId, parentOrder.action(), parentOrder.orderType());

            connector.getClient().placeOrder(masterOrderId, contract, parentOrder);
            connector.getClient().placeOrder(slOrderId, contract, slOrder);
            connector.getClient().placeOrder(tpOrderId, contract, tpOrder);

            // 6. Atualiza as filhas com o ID da IBKR (Imutabilidade)
            OrderDTO updatedSlDto = slDto.withOrderId(slOrderId);
            OrderDTO updatedTpDto = tpDto.withOrderId(tpOrderId);
            List<OrderDTO> updatedChildOrders = List.of(updatedSlDto, updatedTpDto);

            // 7. Cria o DTO Mestra final com o ID da Mestra e a nova lista de filhos.
            OrderDTO finalResultDto = masterOrderDto
                    .withOrderId(masterOrderId)
                    .withChildOrders(updatedChildOrders);

            // Log de RETORNO (Sucesso na submissão ao TWS)
            log.info("✅ [Ponte | EXEC-BRACKET] Ordem Bracket atômica SUBMETIDA para {}. Mestra ID: {}. Retornando DTO com IDs.",
                    masterOrderDto.symbol(), masterOrderId);

            // Retorna o DTO final, imutável e completo.
            return finalResultDto;

        } catch (Exception e) {
            // try-catch para rastrear o que acontece no código
            log.error("❌ [Ponte | API-IBKR] Falha CRÍTICA ao enviar Bracket Order para {}. ID Mestra: {}. Mensagem: {}",
                    masterOrderDto.symbol(), masterOrderId, e.getMessage(), e);
            throw new RuntimeException("Erro ao enviar Bracket Order para a IBKR: " + e.getMessage(), e);
        }
    }

    // --- LÓGICA SIMPLES ---

    private OrderDTO handleSimpleOrder(OrderDTO orderDto) {
        // 1. Obtém um novo ID
        int ibkrOrderId = orderIdManager.getNextOrderId();

        // 2. Criação dos objetos IBKR
        Contract contract = contractFactory.create(orderDto.symbol());
        Order ibkrOrder = orderFactory.create(orderDto, String.valueOf(ibkrOrderId));

        try {
            // Log de ENTRADA DA VENDA/COMPRA
            log.info("🚀 [Ponte | EXEC-SIMPLES] Enviando ordem SIMPLES para TWS. ID IBKR: {}, Ação: {}, Tipo: {}, Ativo: {}.",
                    ibkrOrderId, ibkrOrder.action(), ibkrOrder.orderType(), contract.symbol());

            connector.getClient().placeOrder(ibkrOrderId, contract, ibkrOrder);

            // 3. Cria um NOVO DTO com o ID da IBKR preenchido (Imutabilidade)
            OrderDTO resultDto = orderDto.withOrderId(ibkrOrderId);

            // Log de RETORNO (Sucesso na submissão ao TWS)
            log.info("✅ [Ponte | ORDER-SERVICE] Ordem Simples {} ({}) SUBMETIDA ao TWS. Retornando DTO com ID IBKR: {}",
                    resultDto.clientOrderId(), resultDto.symbol(), resultDto.orderId());

            // Retorna o DTO completo e imutável.
            return resultDto;

        } catch (Exception e) {
            // try-catch para rastrear o que acontece no código
            log.error("❌ [Ponte | API-IBKR] Falha ao enviar Ordem Simples para {}. ID IBKR: {}. Detalhes: {}",
                    orderDto.symbol(), ibkrOrderId, e.getMessage(), e);
            throw new RuntimeException("Erro ao enviar Ordem Simples para a IBKR: " + e.getMessage(), e);
        }
    }
}