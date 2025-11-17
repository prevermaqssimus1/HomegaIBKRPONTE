// Local: com.example.homegaibkrponte.connector.mapper.IBKRMapper.java
package com.example.homegaibkrponte.connector.mapper;

import com.example.homegaibkrponte.dto.WhatIfRequestDTO;
import com.example.homegaibkrponte.model.Order;
import com.example.homegaibkrponte.model.OrderStateDTO;
import com.ib.client.Contract;
import java.math.BigDecimal;
import com.ib.client.OrderState; // <-- Import da classe nativa IBKR

/**
 * Interface responsável por mapear o modelo de Order interno (Principal)
 * para os objetos específicos da IBKR API (Ponte) e vice-versa.
 *
 * Metodologia Aplicada: Padrão Adapter (para isolar o código IBKR do domínio).
 */
public interface IBKRMapper {

    // --- Mapeamento do Domínio para IBKR ---

    Contract toContract(Order order);

    com.ib.client.Order toIBKROrder(Order order);

    /**
     * Converte o símbolo do DTO de Request em um objeto Contract para ser usado no What-If.
     */
    Contract toContract(String symbol);

    /**
     * Cria um objeto Order nativo IBKR com as flags essenciais para a simulação What-If.
     * @param orderId ID de ordem único.
     * @param side Ação (BUY/SELL).
     * @param quantity Quantidade da ordem.
     * @return Ordem nativa com whatIf=true e transmit=false.
     */
    com.ib.client.Order toWhatIfOrder(int orderId, String side, int quantity);

    // --- Mapeamento da IBKR para o Domínio/Ponte ---

    /**
     * Mapeia o objeto nativo OrderState (com.ib.client.OrderState) para o DTO de domínio.
     * @param ibkrOrderState O objeto OrderState recebido no callback openOrder.
     * @return DTO com os valores de margem em String.
     */
    OrderStateDTO toOrderStateDTO(OrderState ibkrOrderState);

    /**
     * Converte o valor de margem em formato String (nativo IBKR) para BigDecimal.
     * Trata valores nulos ou inválidos.
     * @param marginValue Valor da margem (ex: "12567.5" ou "")
     * @return BigDecimal com o valor numérico, ou ZERO.
     */
    BigDecimal parseMarginValue(String marginValue);
}