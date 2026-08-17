package ru.mcs.aiproxy.service;

import org.springframework.stereotype.Service;
import ru.mcs.aiproxy.config.AppProperties;
import ru.mcs.aiproxy.config.ProviderProperties;

@Service
public class ProviderService {
    private final AppProperties properties;

    public ProviderService(AppProperties properties) {
        this.properties = properties;
    }

    public ProviderProperties getProvider(String provider) {
        ProviderProperties config = properties.getProviders().get(provider);
        if (config == null) {
            throw new IllegalArgumentException("Unknown provider: " + provider);
        }
        return config;
    }
}