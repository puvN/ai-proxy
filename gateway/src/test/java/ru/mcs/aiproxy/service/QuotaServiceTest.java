package ru.mcs.aiproxy.service;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.mcs.aiproxy.config.AppProperties;
import ru.mcs.aiproxy.model.QuotaResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuotaServiceTest {

    @Test
    void isAllowedRespectsBothLimits() {
        assertTrue(QuotaService.isAllowed(20, 20L, 40, 40L));
        assertFalse(QuotaService.isAllowed(21, 20L, 1, 40L));
        assertFalse(QuotaService.isAllowed(1, 20L, 41, 40L));
        assertTrue(QuotaService.isAllowed(999, null, 999, null));
        assertTrue(QuotaService.isAllowed(999, -1L, 999, -1L));
    }

    @Test
    void dailyTtlIsSecondsUntilMidnight() {
        var now = ZonedDateTime.of(2026, 8, 18, 12, 0, 0, 0, ZoneOffset.UTC);
        assertEquals(12 * 3600, QuotaService.dailyTtlSeconds(now));
    }

    @Test
    void monthlyTtlIsSecondsUntilFirstOfNextMonth() {
        var now = ZonedDateTime.of(2026, 8, 18, 12, 0, 0, 0, ZoneOffset.UTC);
        var nextMonth = ZonedDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        var expected = Duration.between(now, nextMonth).getSeconds();
        assertEquals(expected, QuotaService.monthlyTtlSeconds(now));
    }

    @Test
    void isModelCallMatchesConfiguredPaths() {
        var props = new AppProperties();
        props.getQuota().setModelPaths(List.of("/openai/v1/chat/completions", "generateContent"));
        var service = new QuotaService(null, props, new ObjectMapper());

        assertTrue(service.isModelCall("/openai/v1/chat/completions"));
        assertTrue(service.isModelCall("/gemini/v1beta/models/x:generateContent"));
        assertFalse(service.isModelCall("/openai/v1/models"));
        assertFalse(service.isModelCall(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void failOpenAllowsWhenRedisDown() {
        var props = new AppProperties();
        props.getQuota().setFailOpen(true);

        var redis = mock(ReactiveStringRedisTemplate.class);
        var valueOps = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(Mono.error(new RuntimeException("redis down")));

        var service = new QuotaService(redis, props, new ObjectMapper());

        StepVerifier.create(service.tryConsume("user-1"))
                .expectNextMatches(QuotaResult::allowed)
                .verifyComplete();
    }
}
