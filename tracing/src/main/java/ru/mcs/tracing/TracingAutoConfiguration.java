package ru.mcs.tracing;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({Tracer.class, Aspect.class})
@ConditionalOnProperty(prefix = "app.tracing", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TracingProperties.class)
public class TracingAutoConfiguration {

    @Bean
    public Tracer tracer(TracingProperties properties) {
        return GlobalOpenTelemetry.get().getTracer(properties.getServiceName());
    }

    @Bean
    public TracingAspect tracingAspect(Tracer tracer) {
        return new TracingAspect(tracer);
    }
}
