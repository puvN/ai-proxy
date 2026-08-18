package ru.mcs.controlplane.dto;

import java.time.Instant;
import java.util.UUID;

import ru.mcs.controlplane.domain.User;

public record UserView(UUID id, String email, String tier, boolean admin, Instant createdAt) {

    public static UserView from(User user) {
        return new UserView(user.getId(), user.getEmail(), user.getTier().name(), user.isAdmin(), user.getCreatedAt());
    }
}
