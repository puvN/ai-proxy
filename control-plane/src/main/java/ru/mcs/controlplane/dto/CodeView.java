package ru.mcs.controlplane.dto;

import java.time.Instant;
import java.util.UUID;

import ru.mcs.controlplane.domain.AccessCode;

public record CodeView(UUID id, String code, String tier, String status, UUID claimedBy,
                       Instant claimedAt, Instant expiresAt, Instant createdAt) {

    public static CodeView from(AccessCode code) {
        return new CodeView(code.getId(), code.getCode(), code.getTier().name(), code.getStatus().name(),
                code.getClaimedBy(), code.getClaimedAt(), code.getExpiresAt(), code.getCreatedAt());
    }
}
