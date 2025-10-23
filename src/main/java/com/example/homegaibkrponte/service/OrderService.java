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
     * O retorno é OrderDTO para que o Principal possa extrair o ID.
     */
    public OrderDTO placeOrder(OrderDTO orderDto) {
        if (!connector.isConnected()) {
            throw new IllegalStateException("Não é possível enviar ordem: Desconectado do TWS/Gateway.");
        }

        log.info("⚙️ [Ponte | ORDER-SERVICE] Recebendo ordem {}: {}", orderDto.clientOrderId(), orderDto.symbol());

        if (orderDto.isBracketOrder()) {
            return handleBracketOrder(orderDto);
        }

        return handleSimpleOrder(orderDto);
    }

    // --- LÓGICA ATÔMICA BRACKET ORDER (Fase 9) ---

    private OrderDTO handleBracketOrder(OrderDTO masterOrderDto) {

        // 1. Validação (Apenas da estrutura)
        if (masterOrderDto.childOrders().size() != 2) {
            log.error("❌ [Ponte | VAL-BRACKET] A Ordem Bracket para {} não contém as 2 ordens de proteção (SL/TP).", masterOrderDto.symbol());
            throw new IllegalStateException("Ordem Composta inválida. Esperado 2 ordens filhas, recebido: " + masterOrderDto.childOrders().size());
        }

        // 2. Separação dos DTOs
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
            parentOrder.transmit(false);
            slOrder.transmit(false);
            tpOrder.transmit(true);

            connector.getClient().placeOrder(masterOrderId, contract, parentOrder);
            connector.getClient().placeOrder(slOrderId, contract, slOrder);
            connector.getClient().placeOrder(tpOrderId, contract, tpOrder);

            // 💡 AJUSTE CRÍTICO (SINERGIA/IMUTABILIDADE): Cria NOVOS DTOs com os IDs preenchidos.

            // 6. Atualiza as filhas com o ID da IBKR
            OrderDTO updatedSlDto = slDto.withOrderId(slOrderId);
            OrderDTO updatedTpDto = tpDto.withOrderId(tpOrderId);
            List<OrderDTO> updatedChildOrders = List.of(updatedSlDto, updatedTpDto);

            // 7. Cria o DTO Mestra final com o ID da Mestra e a nova lista de filhos.
            // (Isto requer o uso do construtor ou helper 'withOrderId' e um helper para 'withChildOrders' no Record)
            OrderDTO finalResultDto = masterOrderDto
                    .withOrderId(masterOrderId) // Atualiza o ID da ordem mestra
                    .withChildOrders(updatedChildOrders); // Assume o helper para atualizar a lista de filhos

            log.info("✅ [Ponte | EXEC-BRACKET] Ordem Bracket atômica enviada para {}. Mestra ID: {}. Retornando DTO com IDs.", masterOrderDto.symbol(), masterOrderId);

            // Retorna o DTO final, imutável e completo.
            return finalResultDto;

        } catch (Exception e) {
            log.error("❌ [Ponte | API-IBKR] Falha CRÍTICA ao enviar Bracket Order para {}. Detalhes: {}", masterOrderDto.symbol(), e.getMessage(), e);
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
            log.info("Validação OK. Enviando ordem simples para o TWS: ID {}, Tipo {}, Ativo {}",
                    ibkrOrderId, ibkrOrder.orderType(), contract.symbol());

            connector.getClient().placeOrder(ibkrOrderId, contract, ibkrOrder);

            // 💡 AJUSTE CRÍTICO (SINERGIA/IMUTABILIDADE): Cria um NOVO DTO
            // com o ID da IBKR preenchido, mantendo a imutabilidade do Record.
            OrderDTO resultDto = orderDto.withOrderId(ibkrOrderId);

            log.info("✅ [Ponte | ORDER-SERVICE] Ordem {} ({}) enviada com sucesso ao TWS. Retornando DTO com ID IBKR: {}",
                    resultDto.clientOrderId(), resultDto.symbol(), resultDto.orderId());

            // Retorna o DTO completo e imutável.
            return resultDto;

        } catch (Exception e) {
            log.error("❌ [Ponte | API-IBKR] Falha ao enviar Ordem Simples para {}. Detalhes: {}", orderDto.symbol(), e.getMessage(), e);
            throw new RuntimeException("Erro ao enviar Ordem Simples para a IBKR: " + e.getMessage(), e);
        }
    }
}