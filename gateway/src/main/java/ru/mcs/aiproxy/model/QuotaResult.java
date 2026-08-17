package ru.mcs.aiproxy.model;

public record QuotaResult(boolean allowed, long dailyUsed, Long dailyLimit, long monthlyUsed, Long monthlyLimit) {

    public static QuotaResult allow(long dailyLimit, long monthlyLimit) {
        return new QuotaResult(true, 0, dailyLimit, 0, monthlyLimit);
    }
}
