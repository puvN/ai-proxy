package ru.mcs.aiproxy.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import ru.mcs.aiproxy.cache.TtlCache;
import ru.mcs.aiproxy.config.AppProperties;

@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayKeyResolver {
    private static final String KEY_PREFIX = "gateway:key:";
    private static final String INVALID = "<invalid>";

    private final ReactiveStringRedisTemplate redis;
    private final AppProperties appProperties;
    private TtlCache<String, String> cache = new TtlCache<>(0);

    @PostConstruct
    void init() {
        cache = new TtlCache<>(appProperties.getGateway().getCacheTtlSeconds() * 1000);
    }

    public Mono<String> resolveUserId(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            log.debug("Gateway key header is empty, skipping gateway auth");
            return Mono.empty();
        }

        var hash = sha256(rawKey);
        var cached = cache.get(hash);
        if (cached != null) {
            if (INVALID.equals(cached)) {
                log.debug("Gateway key negative cache hit, key rejected");
                return Mono.empty();
            }
            log.debug("Gateway key cache hit -> user {}", cached);
            return Mono.just(cached);
        }

        log.debug("Gateway key cache miss, querying redis for {}", KEY_PREFIX + hash);
        return redis.opsForValue().get(KEY_PREFIX + hash)
                .doOnNext(userId -> {
                    cache.put(hash, userId);
                    log.debug("Gateway key resolved -> user {}", userId);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    cache.put(hash, INVALID);
                    log.debug("Gateway key not found, negative cached");
                    return Mono.empty();
                }))
                .onErrorResume(error -> {
                    log.warn("Gateway key lookup failed: {}", error.getMessage());
                    if (appProperties.getGateway().isFailOpen()) {
                        log.debug("Gateway fail-open enabled, using raw key as identity");
                        return Mono.just(rawKey);
                    }
                    return Mono.error(new IllegalStateException("Auth service unavailable", error));
                });
    }

    public static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
