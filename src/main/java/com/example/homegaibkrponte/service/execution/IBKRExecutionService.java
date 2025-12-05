package com.example.homegaibkrponte.service.execution;

// ✅ Contratos da Ponte (desacoplamento do Principal)
import com.example.homegaibkrponte.model.Order;
import com.example.homegaibkrponte.service.order.ExecutionService;
import com.example.homegaibkrponte.service.order.PortfolioUpdateResult;
import com.example.homegaibkrponte.service.order.EmergencyOrder;

import com.example.homegaibkrponte.connector.IBKRConnector;
import com.example.homegaibkrponte.model.Position; // Modelo da Ponte
import com.example.homegaibkrponte.service.execution.mapper.IBKROrderMapper;
import com.ib.client.Contract;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

// Nota: A classe nativa Order da IBKR (com.ib.client.Order) é usada nos métodos
// mapEmergencyOrderToIbkrOrder e placeOrder (assumindo o mapper a retorna).

@Service
@Profile("ponte-ibkr")
@RequiredArgsConstructor
@Slf4j
public class IBKRExecutionService implements ExecutionService {

    private static final String DEFAULT_EXCHANGE = "SMART";

    // Injeção de dependências da Ponte
    private final IBKRConnector connector;
    private final IBKROrderMapper orderMapper;

    /**
     * ✅ Executa uma ordem de resgate de emergência, traduzindo a intenção para Cash Quantity.
     */
    @Override
    public PortfolioUpdateResult executeEmergencyOrder(EmergencyOrder emergencyOrder, Position positionToLiquidate) {

        // Validação da Posição
        if (positionToLiquidate == null) {
            log.error("❌ [EXEC RESGATE CRÍTICO] Posição nula recebida na Ponte. Veto.");
            return PortfolioUpdateResult.failure("Posição nula recebida na Ponte.");
        }

        try {
            log.warn("🚀 [EXEC RESGATE] Preparando ordem de emergência para {} (Valor: R$ {}).",
                    emergencyOrder.getSymbol(), positionToLiquidate.getMarketValue().toPlainString());

            // 1. Mapear: Transforma EmergencyOrder em com.ib.client.Order
            com.ib.client.Order ibkrOrder = orderMapper.mapEmergencyOrderToIbkrOrder(emergencyOrder, positionToLiquidate);

            // 2. Adaptar o Contrato (Certificar-se de usar os metadados corretos da Position)
            Contract contract = createContractFromPosition(positionToLiquidate);

            // 3. Submeter à API TWS (Expondo orderId, Contract, Order)
            connector.placeOrder(ibkrOrder.orderId(), contract, ibkrOrder);

            log.info("✅ [EXEC RESGATE] Ordem de emergência {} submetida com CashQty (R$ {}). ID IBKR: {}",
                    emergencyOrder.getClientId(), ibkrOrder.cashQty(), ibkrOrder.orderId());

            return PortfolioUpdateResult.success("Ordem de resgate enviada via Cash Quantity.");

        } catch (Exception e) {
            log.error("❌ ERRO CRÍTICO ao executar ordem de resgate {} com CashQty. Mensagem: {}",
                    emergencyOrder.getClientId(), e.getMessage(), e);
            return PortfolioUpdateResult.failure("Falha na submissão da ordem de resgate: " + e.getMessage());
        }
    }

    /** Cria um objeto Contract nativo da IBKR a partir do modelo de Posição da Ponte. */
    private Contract createContractFromPosition(Position position) {
        Contract contract = new Contract();

        // Conid é essencial
        contract.conid((int) position.getConId());
        contract.symbol(position.getSymbol());

        // Usando o contrato interno/detalhes (que devem incluir secType e currency)
        if (position.getContractDetails() != null) {
            contract.secType(position.getContractDetails().getOrDefault("secType", "STK"));
            contract.currency(position.getContractDetails().getOrDefault("currency", "USD"));
        } else {
            // Fallback: usar padrões se metadados de contrato estiverem faltando.
            contract.secType("STK");
            contract.currency("USD");
        }

        // A exchange é definida na ORDEM (mapper) para CashQty, mas o Contract
        // também deve ter o roteamento correto ou ser definido como SMART.
        contract.exchange(DEFAULT_EXCHANGE);

        return contract;
    }

    /**
     * Implementação para ordens normais (via fila/agendador).
     */

    public PortfolioUpdateResult executeNewOrder(Order order) {
        log.info("▶️ [EXEC NORMAL] Recebida ordem normal: {}. Implementação pendente.", order.clientOrderId());
        // Lógica de mapeamento e submissão para ordens normais (não CashQty) seria implementada aqui.
        return PortfolioUpdateResult.failure("Execução de nova ordem pendente.");
    }
}