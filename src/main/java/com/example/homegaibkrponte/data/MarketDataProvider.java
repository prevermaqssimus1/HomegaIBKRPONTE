package com.example.homegaibkrponte.data;


import com.example.homegaibkrponte.model.Candle;

import java.util.List;

public interface MarketDataProvider {

    List<Candle> getHistoricalData(String symbol, int years);

    // AJUSTE: O método connect foi simplificado para o nosso modelo de eventos.
    void connect();

    void disconnect();

    void subscribe(String symbol);

    boolean isConnected();
}