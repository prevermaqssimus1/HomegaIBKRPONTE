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
            log.warn("🚀 [EXEC RESGATE] Preparando ordem de emergência para {} (Valor: ${}).",
                    emergencyOrder.getSymbol(), positionToLiquidate.getMarketValue().toPlainString());

            // 1. Mapeia a ordem para o padrão IBKR
            com.ib.client.Order ibkrOrder = orderMapper.mapEmergencyOrderToIbkrOrder(emergencyOrder, positionToLiquidate);
            Contract contract = createContractFromPosition(positionToLiquidate);

            // 2. 🛡️ AJUSTE CRÍTICO: Recuperamos o ID de texto da ordem de emergência
            // Se a EmergencyOrder não tiver um ClientID, geramos um prefixo de resgate
            String principalClientId = (emergencyOrder.getClientId() != null) ?
                    emergencyOrder.getClientId() : "RESCUE-" + emergencyOrder.getSymbol() + "-" + System.currentTimeMillis();

            // 3. ✅ DESPACHO SINCROZINADO:
            // Passamos o ClientID (String) para o novo método que gera o ID numérico internamente.
            connector.placeOrder(principalClientId, contract, ibkrOrder);

            log.info("✅ [EXEC RESGATE] Ordem submetida com sucesso. Ref: {}", principalClientId);
            return PortfolioUpdateResult.success("Ordem de resgate enviada.");

        } catch (Exception e) {
            log.error("❌ ERRO CRÍTICO no resgate: {}", e.getMessage());
            return PortfolioUpdateResult.failure("Falha na submissão: " + e.getMessage());
        }
    }

    /**
     * 🚀 EFETIVAÇÃO DO ENVIO (TRADING NORMAL EUA/JAPÃO)
     * AJUSTADO: Não usa mais Integer.parseInt no clientOrderId.
     * Delega a geração do ID numérico ao Connector e usa a String para rastreio.
     */
    public PortfolioUpdateResult executeNewOrder(Order order) {
        try {
            log.info("▶️ [EXEC NORMAL] Iniciando despacho de {} para a IBKR...", order.symbol());

            // 1. Criar o Contrato Inteligente
            Contract contract = orderMapper.toContract(order.symbol());

            // 2. 🛡️ AJUSTE CRÍTICO: Não tentamos mais converter "HEG_OPEN_MSFT..." em int.
            // Guardamos o ID de texto original para passar ao Connector.
            String principalClientId = order.clientOrderId();

            // 3. Criar a Ordem IBKR
            // O orderId aqui pode ser 0 ou o próximo do connector,
            // pois o método final 'connector.placeOrder' irá sobrescrever com o ID numérico correto.
            com.ib.client.Order ibkrOrder = orderMapper.mapToIbkrOrder(
                    0, // ID temporário
                    order.side().toString(),
                    order.quantity()
            );

            // 4. DESPACHO FÍSICO PARA O CONNECTOR
            // ✅ AGORA USAMOS A ASSINATURA: placeOrder(String, Contract, Order)
            connector.placeOrder(principalClientId, contract, ibkrOrder);

            log.warn("📬📬📬📬📬 [DESPACHADO] Ordem de {} enviada! Ref: {} | Qtd: {} | Lado: {}",
                    order.symbol(), principalClientId, order.quantity(), order.side());

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