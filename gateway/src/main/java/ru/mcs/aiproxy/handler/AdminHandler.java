package ru.mcs.aiproxy.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import ru.mcs.aiproxy.config.AppProperties;
import ru.mcs.aiproxy.service.IpAllowlistService;

@Slf4j
@Component
public class AdminHandler {
    private static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";
    private final AppProperties appProperties;
    private final IpAllowlistService ipAllowlistService;

    public AdminHandler(AppProperties appProperties, IpAllowlistService ipAllowlistService) {
        this.appProperties = appProperties;
        this.ipAllowlistService = ipAllowlistService;
    }

    public Mono<ServerResponse> allowCurrentIp(ServerRequest request) {
        var configuredToken = appProperties.getSecurity().getAdminToken();
        if (configuredToken == null || configuredToken.isBlank()) {
            log.warn("Allow-ip requested but admin token is not configured");
            return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).bodyValue("{\"error\":\"Admin token is not configured\"}");
        }

        var providedToken = request.headers().firstHeader(ADMIN_TOKEN_HEADER);
        if (!configuredToken.equals(providedToken)) {
            log.warn("Allow-ip rejected: invalid admin token");
            return ServerResponse.status(HttpStatus.FORBIDDEN).bodyValue("{\"error\":\"Invalid admin token\"}");
        }

        var remoteAddress = request.remoteAddress().orElse(null);
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            log.warn("Allow-ip rejected: cannot resolve client IP");
            return ServerResponse.status(HttpStatus.BAD_REQUEST).bodyValue("{\"error\":\"Cannot resolve client IP\"}");
        }

        var ip = remoteAddress.getAddress().getHostAddress();
        log.debug("Allow-ip request from {}", ip);
        var expiresAt = ipAllowlistService.allow(ip);
        return ServerResponse.ok().bodyValue("{\"allowedIp\":\"" + ip + "\",\"expiresAt\":\"" + expiresAt + "\"}");
    }
}
