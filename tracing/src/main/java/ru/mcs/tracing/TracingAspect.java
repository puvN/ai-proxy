package ru.mcs.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import reactor.core.publisher.Mono;

@Aspect
public class TracingAspect {

    private final Tracer tracer;

    public TracingAspect(Tracer tracer) {
        this.tracer = tracer;
    }

    @Around("(@within(org.springframework.stereotype.Component) || " +
            "@within(org.springframework.stereotype.Service) || " +
            "@within(org.springframework.stereotype.Repository) || " +
            "@within(org.springframework.stereotype.Controller) || " +
            "@within(org.springframework.web.bind.annotation.RestController)) && " +
            "!@within(org.springframework.context.annotation.Configuration) && " +
            "!@within(org.springframework.boot.context.properties.ConfigurationProperties)")
    public Object trace(ProceedingJoinPoint joinPoint) throws Throwable {
        var signature = joinPoint.getSignature();
        String spanName = signature.getDeclaringType().getSimpleName() + "." + signature.getName();

        Span span = tracer.spanBuilder(spanName)
                .setParent(Context.current())
                .startSpan();

        try {
            Object result = joinPoint.proceed();
            if (result instanceof Mono<?> mono) {
                return mono.doFinally(signal -> span.end());
            }
            span.end();
            return result;
        } catch (Throwable throwable) {
            span.recordException(throwable);
            span.end();
            throw throwable;
        }
    }
}
