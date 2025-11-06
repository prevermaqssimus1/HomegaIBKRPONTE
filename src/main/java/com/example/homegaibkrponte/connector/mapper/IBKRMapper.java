// Local: com.example.homegaibkrponte.connector.mapper.IBKRMapper.java

package com.example.homegaibkrponte.connector.mapper;

import com.example.homegaibkrponte.model.Order;
import com.ib.client.Contract;

/**
 * Interface responsável por mapear o modelo de Order interno (Principal)
 * para os objetos específicos da IBKR API (Ponte).
 */
public interface IBKRMapper {

    /**
     * Converte o objeto Order do Principal para o objeto Contract da IBKR.
     * @param order A ordem do modelo interno.
     * @return O contrato nativo da IBKR.
     */
    Contract toContract(Order order);

    /**
     * Converte o objeto Order do Principal para o objeto Order nativo da IBKR.
     * @param order A ordem do modelo interno.
     * @return A ordem nativa da IBKR.
     */
    com.ib.client.Order toIBKROrder(Order order);
}