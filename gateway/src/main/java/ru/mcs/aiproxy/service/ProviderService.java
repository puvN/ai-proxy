package ru.mcs.aiproxy.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.mcs.aiproxy.config.AppProperties;
import ru.mcs.aiproxy.config.ProviderProperties;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderService {
    private final AppProperties properties;

    public ProviderProperties getProvider(String provider) {
        var config = properties.getProviders().get(provider);
        if (config == null) {
            log.warn("Unknown provider requested: {}", provider);
            throw new IllegalArgumentException("Unknown provider: " + provider);
        }
        log.debug("Resolved provider '{}' -> baseUrl {}", provider, config.getBaseUrl());
        return config;
    }
}
