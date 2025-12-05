package com.example.homegaibkrponte.service.order;

import com.example.homegaibkrponte.model.Position;
import com.example.homegaibkrponte.service.order.PortfolioUpdateResult;

/**
 * Interface de serviço para a execução de ordens na Ponte.
 * Define o contrato para a submissão de ordens do Domínio Principal.
 * Note: A 'Order' e 'EmergencyOrder' aqui referenciadas devem ser versões de contrato
 * ou DTOs que a Ponte está disposta a acoplar (modelos compartilhados/contrato).
 */
public interface ExecutionService {

    // Substitua 'Order' por uma classe de contrato da Ponte se houver
    // PortfolioUpdateResult executeNewOrder(Order order);

    PortfolioUpdateResult executeEmergencyOrder(EmergencyOrder emergencyOrder, Position positionToLiquidate);
}