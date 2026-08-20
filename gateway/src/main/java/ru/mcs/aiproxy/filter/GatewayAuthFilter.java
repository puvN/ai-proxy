package ru.mcs.aiproxy.filter;

import java.nio.charset.StandardCharsets;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;
import ru.mcs.aiproxy.config.AppProperties;
import ru.mcs.aiproxy.model.QuotaResult;
import ru.mcs.aiproxy.service.GatewayKeyResolver;
import ru.mcs.aiproxy.service.QuotaService;

@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayAuthFilter implements WebFilter, Ordered {

    public static final String GATEWAY_USER_ID_ATTR = "ru.mcs.aiproxy.gatewayUserId";

    private final AppProperties appProperties;
    private final GatewayKeyResolver gatewayKeyResolver;
    private final QuotaService quotaService;
    private final MeterRegistry meterRegistry;

    private record Auth(String userId) {
        boolean ok() {
            return userId != null;
        }
    }

    private static final QuotaResult QUOTA_SERVICE_FAILED = new QuotaResult(false, 0, -1L, 0, -1L);

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var path = exchange.getRequest().getPath().value();

        if ("/actuator/health".equals(path) || "/actuator/prometheus".equals(path) || "/admin/allow-ip".equals(path)) {
            return chain.filter(exchange);
        }

        if (!appProperties.getGateway().isEnabled()) {
            log.debug("Gateway mode disabled, skipping gateway auth");
            return chain.filter(exchange);
        }

        var keyHeader = appProperties.getGateway().getKeyHeader();
        var key = exchange.getRequest().getHeaders().getFirst(keyHeader);
        if (key == null || key.isBlank()) {
            log.debug("No {} header, falling back to IP allowlist", keyHeader);
            return chain.filter(exchange);
        }

        log.debug("Gateway request {} {}, gateway key present", exchange.getRequest().getMethod(), path);

        return resolve(exchange, key)
                .flatMap(auth -> auth.ok()
                        ? authorizeAndForward(exchange, chain, path, auth.userId())
                        : Mono.empty())
                .then();
    }

    private Mono<Auth> resolve(ServerWebExchange exchange, String key) {
        return gatewayKeyResolver.resolveUserId(key)
                .map(Auth::new)
                .onErrorResume(error -> {
                    log.error("Gateway auth failed: {}", error.getMessage());
                    return respond(exchange, HttpStatus.SERVICE_UNAVAILABLE, "{\"error\":\"Auth service unavailable\"}")
                            .thenReturn(new Auth(null));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.info("Invalid gateway key rejected: {} {}", exchange.getRequest().getMethod(),
                            exchange.getRequest().getPath().value());
                    meterRegistry.counter("gateway_invalid_key_total").increment();
                    return respond(exchange, HttpStatus.UNAUTHORIZED, "{\"error\":\"Invalid gateway key\"}")
                            .thenReturn(new Auth(null));
                }));
    }

    private Mono<Void> authorizeAndForward(ServerWebExchange exchange, WebFilterChain chain, String path, String userId) {
        exchange.getAttributes().put(GATEWAY_USER_ID_ATTR, userId);

        if (!quotaService.isModelCall(path)) {
            log.debug("Path {} is not a model call, skipping quota", path);
            return chain.filter(exchange);
        }

        return quotaService.tryConsume(userId)
                .onErrorResume(error -> {
                    log.error("Quota service unavailable: {}", error.getMessage());
                    return respond(exchange, HttpStatus.SERVICE_UNAVAILABLE, "{\"error\":\"Quota service unavailable\"}")
                            .thenReturn(QUOTA_SERVICE_FAILED);
                })
                .flatMap(result -> {
                    if (result == QUOTA_SERVICE_FAILED) {
                        return Mono.empty();
                    }
                    addRateLimitHeaders(exchange, result);
                    if (result.allowed()) {
                        return chain.filter(exchange);
                    }
                    log.info("Quota exceeded for user {}: daily {}/{} monthly {}/{}",
                            userId, result.dailyUsed(), result.dailyLimit(), result.monthlyUsed(), result.monthlyLimit());
                    meterRegistry.counter("gateway_quota_exceeded_total").increment();
                    return respond(exchange, HttpStatus.TOO_MANY_REQUESTS, "{\"error\":\"Quota exceeded\"}");
                });
    }

    private void addRateLimitHeaders(ServerWebExchange exchange, QuotaResult result) {
        var headers = exchange.getResponse().getHeaders();
        if (result.dailyLimit() != null && result.dailyLimit() >= 0) {
            headers.add("X-RateLimit-Limit-Daily", String.valueOf(result.dailyLimit()));
            headers.add("X-RateLimit-Remaining-Daily", String.valueOf(Math.max(0, result.dailyLimit() - result.dailyUsed())));
        }
        if (result.monthlyLimit() != null && result.monthlyLimit() >= 0) {
            headers.add("X-RateLimit-Limit-Monthly", String.valueOf(result.monthlyLimit()));
            headers.add("X-RateLimit-Remaining-Monthly", String.valueOf(Math.max(0, result.monthlyLimit() - result.monthlyUsed())));
        }
    }

    private Mono<Void> respond(ServerWebExchange exchange, HttpStatus status, String body) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }
}
