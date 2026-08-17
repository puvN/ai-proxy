package ru.mcs.aiproxy.model;

public record UserLimits(Long daily, Long monthly) {

    public boolean unlimitedDaily() {
        return daily == null || daily < 0;
    }

    public boolean unlimitedMonthly() {
        return monthly == null || monthly < 0;
    }
}
