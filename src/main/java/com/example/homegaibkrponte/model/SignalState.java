package com.example.homegaibkrponte.model;

public enum SignalState {
    PENDING_BUY,      // Sinal recebido, pronto para avaliação
    GDL_EM_EXECUCAO,  // GDL ativada, venda enviada ao broker
    ESPERA_BP,        // Aguardando confirmação assíncrona do novo BP pela Ponte IBKR
    DIVISAO_EM_CURSO, // BP suficiente, executando divisão de ordem
    COMPLETO,         // Ordem/Divisão finalizada com sucesso
    FALHA_RECUPERAVEL, // Falha que permite retry (ex: timeout de BP)
    FALHA_IRRECUPERAVEL // Falha que requer intervenção manual
}