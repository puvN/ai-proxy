package ru.mcs.aiproxy.service;

import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import ru.mcs.aiproxy.cache.TtlCache;
import ru.mcs.aiproxy.config.AppProperties;
import ru.mcs.aiproxy.model.QuotaResult;
import ru.mcs.aiproxy.model.UserLimits;

@Slf4j
@Service
public class QuotaService {
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
            log.debug("Empty user id, skipping quota consumption");
            return Mono.just(QuotaResult.allow(defaultDailyLimit(), defaultMonthlyLimit()));
        }
        return resolveLimits(userId)
                .flatMap(limits -> consume(userId, limits))
                .onErrorResume(this::handleError);
    }

    private Mono<QuotaResult> consume(String userId, UserLimits limits) {
        var now = ZonedDateTime.now(ZoneOffset.UTC);
        var dailyKey = QUOTA_PREFIX + userId + ":daily:" + now.toLocalDate();
        var monthlyKey = QUOTA_PREFIX + userId + ":monthly:" + YearMonth.from(now);
        var keys = List.of(dailyKey, monthlyKey);
        var args = List.of(
                String.valueOf(dailyTtlSeconds(now)),
                String.valueOf(monthlyTtlSeconds(now))
        );

        log.debug("Consuming quota for user {}: limits daily={} monthly={}", userId, limits.daily(), limits.monthly());

        return redis.execute(CONSUME_SCRIPT, keys, args)
                .map(result -> {
                    var dailyUsed = ((Number) result.get(0)).longValue();
                    var monthlyUsed = ((Number) result.get(1)).longValue();
                    var allowed = isAllowed(dailyUsed, limits.daily(), monthlyUsed, limits.monthly());
                    log.debug("Quota for user {}: daily {}/{} monthly {}/{} allowed={}",
                            userId, dailyUsed, limits.daily(), monthlyUsed, limits.monthly(), allowed);
                    return new QuotaResult(allowed, dailyUsed, limits.daily(), monthlyUsed, limits.monthly());
                })
                .next();
    }

    private Mono<QuotaResult> handleError(Throwable error) {
        if (appProperties.getQuota().isFailOpen()) {
            log.warn("Quota check failed, allowing request (fail-open): {}", error.getMessage());
            return Mono.just(QuotaResult.allow(-1, -1));
        }
        log.error("Quota check failed (fail-closed): {}", error.getMessage(), error);
        return Mono.error(new IllegalStateException("Quota service unavailable", error));
    }

    private Mono<UserLimits> resolveLimits(String userId) {
        var cached = limitsCache.get(userId);
        if (cached != null) {
            log.debug("Limits cache hit for user {}: {}", userId, cached);
            return Mono.just(cached);
        }

        var key = LIMITS_PREFIX + userId + LIMITS_SUFFIX;
        log.debug("Limits cache miss for user {}, fetching {}", userId, key);
        return redis.opsForValue().get(key)
                .map(json -> {
                    var parsed = parseLimits(json);
                    log.debug("Limits for user {} from redis: {}", userId, parsed);
                    return parsed;
                })
                .switchIfEmpty(Mono.defer(() -> {
                    var defaults = new UserLimits(defaultDailyLimit(), defaultMonthlyLimit());
                    limitsCache.put(userId, defaults);
                    log.debug("No limits in redis for user {}, using defaults {}", userId, defaults);
                    return Mono.just(defaults);
                }))
                .doOnNext(limits -> limitsCache.put(userId, limits));
    }

    private UserLimits parseLimits(String json) {
        try {
            var limits = objectMapper.readValue(json, UserLimits.class);
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
        var dailyOk = dailyLimit == null || dailyLimit < 0 || dailyUsed <= dailyLimit;
        var monthlyOk = monthlyLimit == null || monthlyLimit < 0 || monthlyUsed <= monthlyLimit;
        return dailyOk && monthlyOk;
    }

    public static long dailyTtlSeconds(ZonedDateTime now) {
        var nextMidnight = now.plusDays(1).truncatedTo(ChronoUnit.DAYS);
        return Math.max(1, Duration.between(now, nextMidnight).getSeconds());
    }

    public static long monthlyTtlSeconds(ZonedDateTime now) {
        var firstOfNextMonth = now.withDayOfMonth(1).plusMonths(1).truncatedTo(ChronoUnit.DAYS);
        return Math.max(1, Duration.between(now, firstOfNextMonth).getSeconds());
    }

    private long defaultDailyLimit() {
        return appProperties.getQuota().getDefaultDailyLimit();
    }

    private long defaultMonthlyLimit() {
        return appProperties.getQuota().getDefaultMonthlyLimit();
    }
}
