package ru.mcs.aiproxy.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private Security security = new Security();
    private Gateway gateway = new Gateway();
    private Quota quota = new Quota();
    private String providersJson = "{}";
    private Map<String, ProviderProperties> providers = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class Security {
        private boolean enabled = true;
        private List<String> allowedIps = new ArrayList<>();
        private String adminToken;
        private long dynamicIpTtlMinutes = 720;
    }

    @Getter
    @Setter
    public static class Gateway {
        private boolean enabled = true;
        private String keyHeader = "X-Gateway-Key";
        private boolean failOpen = false;
        private long cacheTtlSeconds = 60;
    }

    @Getter
    @Setter
    public static class Quota {
        private boolean failOpen = true;
        private long defaultDailyLimit = 20;
        private long defaultMonthlyLimit = 40;
        private long limitsCacheTtlSeconds = 60;
        private List<String> modelPaths = new ArrayList<>(List.of(
                "/openai/v1/chat/completions",
                "/openai/v1/responses",
                "/anthropic/v1/messages",
                "generateContent"
        ));
    }
}
