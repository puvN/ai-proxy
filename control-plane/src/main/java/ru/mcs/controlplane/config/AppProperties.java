package ru.mcs.controlplane.config;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private Admin admin = new Admin();
    private Subscription subscription = new Subscription();

    @Getter
    @Setter
    public static class Admin {
        private List<String> emails = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class Subscription {
        private long freeDailyLimit = 20;
        private long freeMonthlyLimit = 40;
        private long proDailyLimit = -1;
        private long proMonthlyLimit = -1;
    }
}
