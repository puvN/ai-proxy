package ru.mcs.controlplane.dto;

public record UsageResponse(long dailyUsed, Long dailyLimit, long monthlyUsed, Long monthlyLimit) {
}
