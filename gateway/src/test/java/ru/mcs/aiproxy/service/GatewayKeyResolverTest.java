package ru.mcs.aiproxy.service;

import org.junit.jupiter.api.Test;

import reactor.test.StepVerifier;
import ru.mcs.aiproxy.config.AppProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class GatewayKeyResolverTest {

    @Test
    void sha256ProducesStableHex() {
        var hash = GatewayKeyResolver.sha256("secret");
        assertEquals(64, hash.length());
        assertEquals(hash, GatewayKeyResolver.sha256("secret"));
        assertNotEquals(hash, GatewayKeyResolver.sha256("other"));
    }

    @Test
    void resolveUserIdReturnsEmptyForBlankKey() {
        var props = new AppProperties();
        var resolver = new GatewayKeyResolver(null, props);

        StepVerifier.create(resolver.resolveUserId("")).verifyComplete();
        StepVerifier.create(resolver.resolveUserId(null)).verifyComplete();
    }
}
