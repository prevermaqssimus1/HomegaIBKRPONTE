package com.example.homegaibkrponte.factory;

import com.ib.client.Contract;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.List;

/**
 * Padrão Factory: Responsável por criar o objeto Contract nativo da IBKR.
 * ✅ SINERGIA DIAMOND: Integração dinâmica com trading.system.monitored-symbols.
 */
@Component
public class ContractFactory {

    // ✅ Injeta a lista do seu application.properties automaticamente
    @Value("${trading.system.monitored-symbol:AAPL,NVDA,GOOGL,AMZN,TSLA,AMD,CRM,AVGO,PLTR,JPM,GS,LLY,UNH,XOM,VLO,COST,LMT}")
    private String monitoredSymbols;

    // Listas conhecidas de bolsas (para os ativos da sua lista Diamond)
    private static final List<String> NASDAQ_TICKERS = Arrays.asList(
            "AAPL", "NVDA", "GOOGL", "AMZN", "TSLA", "AMD", "AVGO", "COST"
    );

    /**
     * Cria um Contract IBKR configurado.
     * @param symbol O ticker do ativo.
     * @return Objeto Contract configurado.
     */
    public Contract create(String symbol) {
        Contract contract = new Contract();
        String cleanSymbol = symbol.toUpperCase().trim();

        contract.symbol(cleanSymbol);
        contract.secType("STK");

        // 1. Lógica de Mercado (Suporte a BR e US)
        if (cleanSymbol.endsWith(".SA")) {
            contract.symbol(cleanSymbol.replace(".SA", ""));
            contract.currency("BRL");
            contract.exchange("BVMF");
        } else {
            contract.currency("USD");
            contract.exchange("SMART");

            // 🛡️ SINERGIA CRÍTICA: Resolve erro de contrato ambíguo (Ex: PLTR)
            // Define Primary Exchange baseada na listagem real do ativo
            if (isNasdaq(cleanSymbol)) {
                contract.primaryExch("NASDAQ");
            } else {
                contract.primaryExch("NYSE");
            }
        }

        return contract;
    }

    /**
     * 📊 Inteligência de Roteamento:
     * Verifica se o ativo pertence à NASDAQ. Se não estiver na lista ou for NYSE, retorna false.
     */
    private boolean isNasdaq(String symbol) {
        return NASDAQ_TICKERS.contains(symbol);
    }

    /**
     * ✅ Útil para auditoria: Verifica se o símbolo enviado está na sua lista monitorada.
     */
    public boolean isMonitored(String symbol) {
        List<String> list = Arrays.asList(monitoredSymbols.split(","));
        return list.contains(symbol.toUpperCase().trim());
    }
}