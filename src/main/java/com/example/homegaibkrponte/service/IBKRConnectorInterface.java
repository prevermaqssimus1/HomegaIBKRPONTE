package com.example.homegaibkrponte.service;
import com.example.homegaibkrponte.model.OrdemCompra;
import com.example.homegaibkrponte.model.SinalVenda;

public interface IBKRConnectorInterface {

    // Método para a GDL: Recebe o DTO do Principal e envia a Venda para a IBKR.
    void enviarOrdemDeVenda(SinalVenda venda);

    // Método para o ciclo normal: Envia a ordem de compra fracionada.
    void enviarOrdemDeCompra(OrdemCompra compra);

    // Injeta o Listener do Principal na Ponte para receber notificações de BP.
    void setBPSyncedListener(BPSyncedListener listener);
}