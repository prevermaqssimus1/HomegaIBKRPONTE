package com.example.homegaibkrponte.factory;

import com.ib.client.Contract;
import org.springframework.stereotype.Component;

/**
 * Padrão Factory: Responsável por criar o objeto Contract nativo da IBKR
 * a partir do símbolo do ativo, isolando o OrderService dos detalhes de configuração.
 */
@Component
public class ContractFactory {

    /**
     * Cria um Contract IBKR configurado.
     * @param symbol O ticker do ativo (ex: "NVDA").
     * @return Objeto Contract configurado.
     */
    public Contract create(String symbol) {
        Contract contract = new Contract();
        contract.symbol(symbol);

        // Configurações Padrão IBKR (Exemplo para Ações dos EUA - SMART/USD)
        contract.secType("STK");        // Tipo de segurança: Ação
        contract.currency("USD");       // Moeda
        contract.exchange("SMART");     // Exchange principal/Roteamento inteligente

        // Adicionar campos opcionais aqui, se necessário (ex: PrimaryExchange)

        return contract;
    }
}