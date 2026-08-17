package ru.mcs.aiproxy.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import ru.mcs.aiproxy.config.AppProperties;
import ru.mcs.aiproxy.service.IpAllowlistService;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class AuthFilter implements WebFilter {
    private final AppProperties appProperties;
    private final IpAllowlistService ipAllowlistService;

    public AuthFilter(AppProperties appProperties, IpAllowlistService ipAllowlistService) {
        this.appProperties = appProperties;
        this.ipAllowlistService = ipAllowlistService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {

        var path = exchange.getRequest().getPath().value();
        if ("/actuator/health".equals(path)) {
            return chain.filter(exchange);
        }
        if ("/admin/allow-ip".equals(path)) {
            return chain.filter(exchange);
        }
        if (exchange.getAttribute(GatewayAuthFilter.GATEWAY_USER_ID_ATTR) != null) {
            log.debug("Request already authenticated via gateway key, skipping IP allowlist");
            return chain.filter(exchange);
        }
        var security = appProperties.getSecurity();

        if (security == null || !security.isEnabled()) {
            log.debug("IP allowlist disabled, allowing {}", path);
            return chain.filter(exchange);
        }
        var remoteAddress = resolveRemoteAddress(exchange);
        if (remoteAddress == null) {
            log.warn("Cannot resolve client IP for {}", path);
            return forbidden(exchange, "Cannot resolve client IP");
        }
        if (ipAllowlistService.isAllowed(remoteAddress)) {
            log.debug("IP {} allowed for {}", remoteAddress, path);
            return chain.filter(exchange);
        }
        log.info("IP {} blocked for {}", remoteAddress, path);
        return forbidden(exchange, "IP not allowed: " + remoteAddress);
    }

    private String resolveRemoteAddress(ServerWebExchange exchange) {
        var remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return null;
        }
        return remoteAddress.getAddress().getHostAddress();
    }

    private Mono<Void> forbidden(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        var body = ("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);

        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
}
