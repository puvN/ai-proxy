package ru.mcs.aiproxy.config;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ProviderConfigLoader implements ApplicationRunner {

    private final AppProperties appProperties;

    private final ObjectMapper objectMapper;


    public ProviderConfigLoader(
            AppProperties appProperties,
            ObjectMapper objectMapper
    ) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }


    @Override
    public void run(ApplicationArguments args) throws Exception {

        var json = appProperties.getProvidersJson();

        if (json == null || json.isBlank()) {
            log.debug("PROVIDERS_JSON is empty or blank, no providers configured");
            return;
        }

        Map<String, Map<String, String>> raw =
                objectMapper.readValue(
                        json,
                        objectMapper.getTypeFactory()
                                .constructMapType(
                                        LinkedHashMap.class,
                                        String.class,
                                        Map.class
                                )
                );

        var providers = new LinkedHashMap<String, ProviderProperties>();

        raw.forEach((name, fields) -> {

            var provider = new ProviderProperties();
            provider.setBaseUrl(fields.get("baseUrl"));

            providers.put(name, provider);

        });

        appProperties.setProviders(providers);

        log.info("Loaded {} providers: {}", providers.size(), providers.keySet());
        log.debug("Provider configuration: {}", raw);

    }

}
