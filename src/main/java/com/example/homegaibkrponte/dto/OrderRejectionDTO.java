package com.example.homegaibkrponte.dto;

// DTO para representar uma rejeição crítica de ordem da corretora
public record OrderRejectionDTO(
        int brokerOrderId,
        int errorCode,
        String rejectionReason
) {}