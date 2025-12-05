package com.example.homegaibkrponte.model;

import java.math.BigDecimal;
import java.util.UUID;

// 2. Estado do Sinal de Compra (Inclui o estado da máquina)
public record SinalCompra(
        UUID signalId,
        String ativo,
        BigDecimal custoTotal,
        SignalState estado
) {
    public SinalCompra comNovoEstado(SignalState novoEstado) {
        return new SinalCompra(this.signalId, this.ativo, this.custoTotal, novoEstado);
    }
}