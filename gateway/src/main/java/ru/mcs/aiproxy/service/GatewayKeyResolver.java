package ru.mcs.aiproxy.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import ru.mcs.aiproxy.cache.TtlCache;
import ru.mcs.aiproxy.config.AppProperties;

@Service
public class GatewayKeyResolver {
    private static final Logger log = LoggerFactory.getLogger(GatewayKeyResolver.class);
    private static final String KEY_PREFIX = "gateway:key:";
    private static final String INVALID = "<invalid>";

    private final ReactiveStringRedisTemplate redis;
    private final AppProperties appProperties;
    private final TtlCache<String, String> cache;

    public GatewayKeyResolver(ReactiveStringRedisTemplate redis, AppProperties appProperties) {
        this.redis = redis;
        this.appProperties = appProperties;
        this.cache = new TtlCache<>(appProperties.getGateway().getCacheTtlSeconds() * 1000);
    }

    public Mono<String> resolveUserId(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return Mono.empty();
        }

        String hash = sha256(rawKey);
        String cached = cache.get(hash);
        if (cached != null) {
            return INVALID.equals(cached) ? Mono.empty() : Mono.just(cached);
        }

        return redis.opsForValue().get(KEY_PREFIX + hash)
                .switchIfEmpty(Mono.defer(() -> {
                    cache.put(hash, INVALID);
                    return Mono.empty();
                }))
                .doOnNext(userId -> cache.put(hash, userId))
                .onErrorResume(error -> {
                    log.warn("Gateway key lookup failed: {}", error.getMessage());
                    if (appProperties.getGateway().isFailOpen()) {
                        return Mono.just(rawKey);
                    }
                    return Mono.error(new IllegalStateException("Auth service unavailable", error));
                });
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
