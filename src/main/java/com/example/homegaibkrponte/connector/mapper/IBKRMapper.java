package com.example.homegaibkrponte.connector.mapper;

import java.util.List;
import java.math.BigDecimal;

public interface IBKRMapper {

    com.ib.client.Contract toContract(com.example.homegaibkrponte.model.Order order);

    com.ib.client.Contract toContract(String symbol);

    // Converte para Ordem Simples Nativa
    com.ib.client.Order toIBKROrder(com.example.homegaibkrponte.model.Order domainOrder);

    // Converte para o Combo Bracket (Pai + SL + TP)
    List<com.ib.client.Order> toBracketOrder(com.example.homegaibkrponte.model.Order domainOrder);

    com.ib.client.Order toWhatIfOrder(int orderId, String side, int quantity);

    com.example.homegaibkrponte.model.OrderStateDTO toOrderStateDTO(com.ib.client.OrderState ibkrOrderState);

    BigDecimal parseMarginValue(String marginValue);
}