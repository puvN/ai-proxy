package ru.mcs.aiproxy.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.mcs.aiproxy.config.AppProperties;
import ru.mcs.aiproxy.filter.IpAddressMatcher;

@Slf4j
@Service
public class IpAllowlistService {
    private final AppProperties appProperties;
    private final Map<String, Instant> dynamicIps = new ConcurrentHashMap<>();

    public IpAllowlistService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public boolean isAllowed(String remoteAddress) {
        var staticMatch = appProperties.getSecurity().getAllowedIps().stream()
                .anyMatch(pattern -> new IpAddressMatcher(pattern).matches(remoteAddress));

        if (staticMatch) {
            log.debug("IP {} matched static allowlist", remoteAddress);
            return true;
        }
        cleanupExpired();
        var dynamic = dynamicIps.containsKey(remoteAddress);
        log.debug("IP {} allowed by dynamic allowlist: {}", remoteAddress, dynamic);
        return dynamic;
    }

    public Instant allow(String remoteAddress) {
        var ttlMinutes = appProperties.getSecurity().getDynamicIpTtlMinutes();
        var expiresAt = Instant.now().plus(Duration.ofMinutes(ttlMinutes));
        dynamicIps.put(remoteAddress, expiresAt);
        log.info("IP {} added to allowlist until {}", remoteAddress, expiresAt);
        return expiresAt;
    }

    private void cleanupExpired() {
        var now = Instant.now();
        var removed = dynamicIps.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
        if (removed) {
            log.debug("Removed expired dynamic allowlist entries");
        }
    }
}
