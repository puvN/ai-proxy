package ru.mcs.controlplane.dto;

import ru.mcs.controlplane.domain.ApiKey;

public record CreatedKey(String plaintext, ApiKey key) {
}
