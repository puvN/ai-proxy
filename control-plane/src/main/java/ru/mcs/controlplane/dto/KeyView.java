package ru.mcs.controlplane.dto;

import java.time.Instant;
import java.util.UUID;

import ru.mcs.controlplane.domain.ApiKey;

public record KeyView(UUID id, String prefix, Instant createdAt, Instant revokedAt) {

    public static KeyView from(ApiKey key) {
        return new KeyView(key.getId(), key.getKeyPrefix(), key.getCreatedAt(), key.getRevokedAt());
    }
}
