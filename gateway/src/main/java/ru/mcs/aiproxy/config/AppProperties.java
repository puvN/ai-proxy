package ru.mcs.aiproxy.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private Security security = new Security();
    private Gateway gateway = new Gateway();
    private Quota quota = new Quota();
    private String providersJson = "{}";
    private Map<String, ProviderProperties> providers = new LinkedHashMap<>();

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public Gateway getGateway() {
        return gateway;
    }

    public void setGateway(Gateway gateway) {
        this.gateway = gateway;
    }

    public Quota getQuota() {
        return quota;
    }

    public void setQuota(Quota quota) {
        this.quota = quota;
    }

    public String getProvidersJson() {
        return providersJson;
    }

    public void setProvidersJson(String providersJson) {
        this.providersJson = providersJson;
    }

    public Map<String, ProviderProperties> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderProperties> providers) {
        this.providers = providers;
    }


    public static class Security {
        private boolean enabled = true;
        private List<String> allowedIps = new ArrayList<>();
        private String adminToken;
        private long dynamicIpTtlMinutes = 720;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getAllowedIps() {
            return allowedIps;
        }

        public void setAllowedIps(List<String> allowedIps) {
            this.allowedIps = allowedIps;
        }

        public String getAdminToken() {
            return adminToken;
        }

        public void setAdminToken(String adminToken) {
            this.adminToken = adminToken;
        }

        public long getDynamicIpTtlMinutes() {
            return dynamicIpTtlMinutes;
        }

        public void setDynamicIpTtlMinutes(long dynamicIpTtlMinutes) {
            this.dynamicIpTtlMinutes = dynamicIpTtlMinutes;
        }
    }

    public static class Gateway {
        private boolean enabled = true;
        private String keyHeader = "X-Gateway-Key";
        private boolean failOpen = false;
        private long cacheTtlSeconds = 60;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getKeyHeader() {
            return keyHeader;
        }

        public void setKeyHeader(String keyHeader) {
            this.keyHeader = keyHeader;
        }

        public boolean isFailOpen() {
            return failOpen;
        }

        public void setFailOpen(boolean failOpen) {
            this.failOpen = failOpen;
        }

        public long getCacheTtlSeconds() {
            return cacheTtlSeconds;
        }

        public void setCacheTtlSeconds(long cacheTtlSeconds) {
            this.cacheTtlSeconds = cacheTtlSeconds;
        }
    }

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

        public boolean isFailOpen() {
            return failOpen;
        }

        public void setFailOpen(boolean failOpen) {
            this.failOpen = failOpen;
        }

        public long getDefaultDailyLimit() {
            return defaultDailyLimit;
        }

        public void setDefaultDailyLimit(long defaultDailyLimit) {
            this.defaultDailyLimit = defaultDailyLimit;
        }

        public long getDefaultMonthlyLimit() {
            return defaultMonthlyLimit;
        }

        public void setDefaultMonthlyLimit(long defaultMonthlyLimit) {
            this.defaultMonthlyLimit = defaultMonthlyLimit;
        }

        public long getLimitsCacheTtlSeconds() {
            return limitsCacheTtlSeconds;
        }

        public void setLimitsCacheTtlSeconds(long limitsCacheTtlSeconds) {
            this.limitsCacheTtlSeconds = limitsCacheTtlSeconds;
        }

        public List<String> getModelPaths() {
            return modelPaths;
        }

        public void setModelPaths(List<String> modelPaths) {
            this.modelPaths = modelPaths;
        }
    }
}
