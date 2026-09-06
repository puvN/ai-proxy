package ru.mcs.controlplane.service;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import ru.mcs.controlplane.domain.Tier;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisGatewaySyncService {

    private static final String KEY_PREFIX = "gateway:key:";
    private static final String LIMITS_PREFIX = "gateway:user:";
    private static final String LIMITS_SUFFIX = ":limits";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final LimitsService limitsService;

    public void syncKey(UUID userId, String keyHash) {
        try {
            redis.opsForValue().set(KEY_PREFIX + keyHash, String.valueOf(userId));
            log.debug("Synced gateway key for user {}", userId);
        } catch (Exception e) {
            log.warn("Failed to sync gateway key to Redis: {}", e.getMessage());
        }
    }

    public void removeKey(String keyHash) {
        try {
            redis.delete(KEY_PREFIX + keyHash);
            log.debug("Removed gateway key from Redis");
        } catch (Exception e) {
            log.warn("Failed to remove gateway key from Redis: {}", e.getMessage());
        }
    }

    public void syncLimits(UUID userId, Tier tier) {
        try {
            var limits = limitsService.forTier(tier);
            redis.opsForValue().set(LIMITS_PREFIX + userId + LIMITS_SUFFIX, objectMapper.writeValueAsString(limits));
            log.debug("Synced limits for user {} ({}) -> {}", userId, tier, limits);
        } catch (Exception e) {
            log.warn("Failed to sync limits to Redis: {}", e.getMessage());
        }
    }
}
