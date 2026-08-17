package ru.mcs.aiproxy.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import ru.mcs.aiproxy.cache.TtlCache;
import ru.mcs.aiproxy.config.AppProperties;
import ru.mcs.aiproxy.model.QuotaResult;
import ru.mcs.aiproxy.model.UserLimits;

@Service
public class QuotaService {
    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);
    private static final String LIMITS_PREFIX = "gateway:user:";
    private static final String LIMITS_SUFFIX = ":limits";
    private static final String QUOTA_PREFIX = "quota:";

    private static final RedisScript<List> CONSUME_SCRIPT = new DefaultRedisScript<>(
            "local d = redis.call('INCR', KEYS[1]) " +
                    "if tonumber(ARGV[1]) > 0 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
                    "local m = redis.call('INCR', KEYS[2]) " +
                    "if tonumber(ARGV[2]) > 0 then redis.call('EXPIRE', KEYS[2], ARGV[2]) end " +
                    "return { d, m }",
            List.class
    );

    private final ReactiveStringRedisTemplate redis;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final TtlCache<String, UserLimits> limitsCache;

    public QuotaService(ReactiveStringRedisTemplate redis, AppProperties appProperties, ObjectMapper objectMapper) {
        this.redis = redis;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.limitsCache = new TtlCache<>(appProperties.getQuota().getLimitsCacheTtlSeconds() * 1000);
    }

    public boolean isModelCall(String path) {
        if (path == null) {
            return false;
        }
        return appProperties.getQuota().getModelPaths().stream().anyMatch(path::contains);
    }

    public Mono<QuotaResult> tryConsume(String userId) {
        if (userId == null || userId.isBlank()) {
            return Mono.just(QuotaResult.allow(defaultDailyLimit(), defaultMonthlyLimit()));
        }
        return resolveLimits(userId)
                .flatMap(limits -> consume(userId, limits))
                .onErrorResume(this::handleError);
    }

    private Mono<QuotaResult> consume(String userId, UserLimits limits) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String dailyKey = QUOTA_PREFIX + userId + ":daily:" + now.toLocalDate();
        String monthlyKey = QUOTA_PREFIX + userId + ":monthly:" + YearMonth.from(now);
        List<String> keys = List.of(dailyKey, monthlyKey);
        List<String> args = List.of(
                String.valueOf(dailyTtlSeconds(now)),
                String.valueOf(monthlyTtlSeconds(now))
        );

        return redis.execute(CONSUME_SCRIPT, keys, args)
                .map(result -> {
                    long dailyUsed = ((Number) result.get(0)).longValue();
                    long monthlyUsed = ((Number) result.get(1)).longValue();
                    boolean allowed = isAllowed(dailyUsed, limits.daily(), monthlyUsed, limits.monthly());
                    return new QuotaResult(allowed, dailyUsed, limits.daily(), monthlyUsed, limits.monthly());
                })
                .next();
    }

    private Mono<QuotaResult> handleError(Throwable error) {
        if (appProperties.getQuota().isFailOpen()) {
            log.warn("Quota check failed, allowing request (fail-open): {}", error.getMessage());
            return Mono.just(QuotaResult.allow(-1, -1));
        }
        return Mono.error(new IllegalStateException("Quota service unavailable", error));
    }

    private Mono<UserLimits> resolveLimits(String userId) {
        UserLimits cached = limitsCache.get(userId);
        if (cached != null) {
            return Mono.just(cached);
        }

        String key = LIMITS_PREFIX + userId + LIMITS_SUFFIX;
        return redis.opsForValue().get(key)
                .map(this::parseLimits)
                .switchIfEmpty(Mono.defer(() -> {
                    UserLimits defaults = new UserLimits(defaultDailyLimit(), defaultMonthlyLimit());
                    limitsCache.put(userId, defaults);
                    return Mono.just(defaults);
                }))
                .doOnNext(limits -> limitsCache.put(userId, limits));
    }

    private UserLimits parseLimits(String json) {
        try {
            UserLimits limits = objectMapper.readValue(json, UserLimits.class);
            return new UserLimits(
                    limits.daily() == null || limits.daily() < 0 ? null : limits.daily(),
                    limits.monthly() == null || limits.monthly() < 0 ? null : limits.monthly()
            );
        } catch (Exception e) {
            log.warn("Failed to parse user limits, falling back to defaults: {}", e.getMessage());
            return new UserLimits(defaultDailyLimit(), defaultMonthlyLimit());
        }
    }

    public static boolean isAllowed(long dailyUsed, Long dailyLimit, long monthlyUsed, Long monthlyLimit) {
        boolean dailyOk = dailyLimit == null || dailyLimit < 0 || dailyUsed <= dailyLimit;
        boolean monthlyOk = monthlyLimit == null || monthlyLimit < 0 || monthlyUsed <= monthlyLimit;
        return dailyOk && monthlyOk;
    }

    public static long dailyTtlSeconds(ZonedDateTime now) {
        ZonedDateTime nextMidnight = now.plusDays(1).truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        return Math.max(1, Duration.between(now, nextMidnight).getSeconds());
    }

    public static long monthlyTtlSeconds(ZonedDateTime now) {
        ZonedDateTime firstOfNextMonth = now.withDayOfMonth(1).plusMonths(1).truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        return Math.max(1, Duration.between(now, firstOfNextMonth).getSeconds());
    }

    private long defaultDailyLimit() {
        return appProperties.getQuota().getDefaultDailyLimit();
    }

    private long defaultMonthlyLimit() {
        return appProperties.getQuota().getDefaultMonthlyLimit();
    }
}
