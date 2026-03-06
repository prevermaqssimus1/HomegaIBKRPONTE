package com.example.homegaibkrponte.service.execution;

import com.example.homegaibkrponte.model.Order;
import com.example.homegaibkrponte.service.order.ExecutionService;
import com.example.homegaibkrponte.service.order.PortfolioUpdateResult;
import com.example.homegaibkrponte.service.order.EmergencyOrder;
import com.example.homegaibkrponte.connector.IBKRConnector;
import com.example.homegaibkrponte.model.Position;
import com.example.homegaibkrponte.service.execution.mapper.IBKROrderMapper;
import com.ib.client.Contract;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("ponte-ibkr")
@RequiredArgsConstructor
@Slf4j
public class IBKRExecutionService implements ExecutionService {

    private static final String DEFAULT_EXCHANGE = "SMART";

    private final IBKRConnector connector;
    private final IBKROrderMapper orderMapper;

    /**
     * ✅ MANTIDO: Executa uma ordem de resgate de emergência (Cash Quantity).
     */
    @Override
    public PortfolioUpdateResult executeEmergencyOrder(EmergencyOrder emergencyOrder, Position positionToLiquidate) {
        if (positionToLiquidate == null) {
            log.error("❌ [EXEC RESGATE CRÍTICO] Posição nula recebida na Ponte. Veto.");
            return PortfolioUpdateResult.failure("Posição nula recebida na Ponte.");
        }

        try {
            log.warn("🚀 [EXEC RESGATE] Preparando ordem de emergência para {} (Valor: R$ {}).",
                    emergencyOrder.getSymbol(), positionToLiquidate.getMarketValue().toPlainString());

            com.ib.client.Order ibkrOrder = orderMapper.mapEmergencyOrderToIbkrOrder(emergencyOrder, positionToLiquidate);
            Contract contract = createContractFromPosition(positionToLiquidate);

            connector.placeOrder(ibkrOrder.orderId(), contract, ibkrOrder);

            log.info("✅ [EXEC RESGATE] Ordem submetida com CashQty. ID IBKR: {}", ibkrOrder.orderId());
            return PortfolioUpdateResult.success("Ordem de resgate enviada.");

        } catch (Exception e) {
            log.error("❌ ERRO CRÍTICO no resgate: {}", e.getMessage());
            return PortfolioUpdateResult.failure("Falha na submissão: " + e.getMessage());
        }
    }

    /**
     * 🚀 EFETIVAÇÃO DO ENVIO (TRADING NORMAL EUA/JAPÃO)
     * Este método agora é o motor que despacha as ordens do Orquestrador.
     */

    public PortfolioUpdateResult executeNewOrder(Order order) {
        try {
            log.info("▶️ [EXEC NORMAL] Iniciando despacho de {} para a IBKR...", order.symbol());

            // 1. Criar o Contrato Inteligente (Lógica de Moeda e Bolsa integrada no Mapper)
            // Se symbol termina com .T -> JPY/TSEJ | Se não -> USD/SMART
            Contract contract = orderMapper.toContract(order.symbol());

            // 2. Extrair o ID da Ordem do contrato Principal
            int orderId = Integer.parseInt(order.clientOrderId());

            // 3. Criar a Ordem IBKR baseada em QUANTIDADE (Shares)
            // Diferente do resgate, aqui usamos unidades físicas calculadas pelo Sizing.
            com.ib.client.Order ibkrOrder = orderMapper.mapToIbkrOrder(
                    orderId,
                    order.side().toString(),
                    order.quantity()
            );

            // 4. DESPACHO FÍSICO PARA A TWS/GATEWAY
            connector.placeOrder(orderId, contract, ibkrOrder);

            log.warn("📬📬📬 [DESPACHADO] Ordem de {} enviada! ID: {} | Qtd: {} | Lado: {}",
                    order.symbol(), orderId, order.quantity(), order.side());

            return PortfolioUpdateResult.success("Ordem enviada com sucesso para a fila da corretora.");

        } catch (Exception e) {
            log.error("❌ [FALHA-DESPACHO] Erro ao processar ordem normal de {}: {}", order.symbol(), e.getMessage());
            return PortfolioUpdateResult.failure("Erro no envio: " + e.getMessage());
        }
    }

    /** Cria um objeto Contract nativo a partir do modelo de Posição (MANTIDO). */
    private Contract createContractFromPosition(Position position) {
        Contract contract = new Contract();
        contract.conid((int) position.getConId());
        contract.symbol(position.getSymbol());

        if (position.getContractDetails() != null) {
            contract.secType(position.getContractDetails().getOrDefault("secType", "STK"));
            contract.currency(position.getContractDetails().getOrDefault("currency", "USD"));
        } else {
            contract.secType("STK");
            contract.currency("USD");
        }
        contract.exchange(DEFAULT_EXCHANGE);
        return contract;
    }
}