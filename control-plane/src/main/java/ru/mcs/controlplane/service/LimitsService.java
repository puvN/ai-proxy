package ru.mcs.controlplane.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.mcs.controlplane.config.AppProperties;
import ru.mcs.controlplane.domain.Tier;
import ru.mcs.controlplane.dto.TierLimits;

@Service
@RequiredArgsConstructor
public class LimitsService {

    private final AppProperties appProperties;

    public TierLimits forTier(Tier tier) {
        var sub = appProperties.getSubscription();
        return switch (tier) {
            case FREE -> new TierLimits(norm(sub.getFreeDailyLimit()), norm(sub.getFreeMonthlyLimit()));
            case PRO -> new TierLimits(norm(sub.getProDailyLimit()), norm(sub.getProMonthlyLimit()));
        };
    }

    private static Long norm(long value) {
        return value < 0 ? null : value;
    }
}
