package ru.mcs.aiproxy.filter;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

@Component
public class GatewayAuthFilter implements WebFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(GatewayAuthFilter.class);

    public static final String GATEWAY_USER_ID_ATTR = "ru.mcs.aiproxy.gatewayUserId";

    private final AppProperties appProperties;
    private final GatewayKeyResolver gatewayKeyResolver;
    private final QuotaService quotaService;

    public GatewayAuthFilter(AppProperties appProperties, GatewayKeyResolver gatewayKeyResolver, QuotaService quotaService) {
        this.appProperties = appProperties;
        this.gatewayKeyResolver = gatewayKeyResolver;
        this.quotaService = quotaService;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        if ("/actuator/health".equals(path) || "/admin/allow-ip".equals(path)) {
            return chain.filter(exchange);
        }

        if (!appProperties.getGateway().isEnabled()) {
            return chain.filter(exchange);
        }

        String key = exchange.getRequest().getHeaders().getFirst(appProperties.getGateway().getKeyHeader());
        if (key == null || key.isBlank()) {
            return chain.filter(exchange);
        }

        return gatewayKeyResolver.resolveUserId(key)
                .flatMap(userId -> {
                    exchange.getAttributes().put(GATEWAY_USER_ID_ATTR, userId);
                    if (!quotaService.isModelCall(path)) {
                        return chain.filter(exchange);
                    }
                    return quotaService.tryConsume(userId).flatMap(result -> {
                        addRateLimitHeaders(exchange, result);
                        if (result.allowed()) {
                            return chain.filter(exchange);
                        }
                        return respond(exchange, HttpStatus.TOO_MANY_REQUESTS,
                                "{\"error\":\"Quota exceeded\"}");
                    });
                })
                .switchIfEmpty(respond(exchange, HttpStatus.UNAUTHORIZED,
                        "{\"error\":\"Invalid gateway key\"}"))
                .onErrorResume(error -> {
                    log.error("Gateway auth failed: {}", error.getMessage());
                    return respond(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                            "{\"error\":\"Auth service unavailable\"}");
                });
    }

    private void addRateLimitHeaders(ServerWebExchange exchange, QuotaResult result) {
        HttpHeaders headers = exchange.getResponse().getHeaders();
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
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }
}
