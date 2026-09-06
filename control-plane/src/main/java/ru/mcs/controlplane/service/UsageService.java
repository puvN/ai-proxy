package ru.mcs.controlplane.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import ru.mcs.controlplane.domain.Tier;
import ru.mcs.controlplane.dto.UsageResponse;

@Slf4j
@Service
@RequiredArgsConstructor
public class UsageService {

    private final StringRedisTemplate redis;
    private final LimitsService limitsService;

    public UsageResponse usage(UUID userId, Tier tier) {
        var dailyKey = "quota:" + userId + ":daily:" + LocalDate.now(ZoneOffset.UTC);
        var monthlyKey = "quota:" + userId + ":monthly:" + YearMonth.now(ZoneOffset.UTC);
        var dailyUsed = getCounter(dailyKey);
        var monthlyUsed = getCounter(monthlyKey);
        var limits = limitsService.forTier(tier);
        return new UsageResponse(dailyUsed, limits.daily(), monthlyUsed, limits.monthly());
    }

    private long getCounter(String key) {
        try {
            var value = redis.opsForValue().get(key);
            return value == null ? 0 : Long.parseLong(value);
        } catch (Exception e) {
            log.warn("Failed to read usage counter {}: {}", key, e.getMessage());
            return 0;
        }
    }
}
