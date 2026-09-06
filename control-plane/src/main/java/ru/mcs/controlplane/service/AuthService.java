package ru.mcs.controlplane.service;

import java.time.Instant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.mcs.controlplane.config.AppProperties;
import ru.mcs.controlplane.domain.Tier;
import ru.mcs.controlplane.domain.User;
import ru.mcs.controlplane.repository.UserRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;
    private final RedisGatewaySyncService redisSync;

    @Transactional
    public User register(String email, String password) {
        var normalized = email == null ? "" : email.trim().toLowerCase();
        if (normalized.isBlank() || password == null || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email and password are required");
        }
        if (userRepository.existsByEmail(normalized)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }

        var isAdmin = appProperties.getAdmin().getEmails().stream()
                .anyMatch(e -> e != null && e.equalsIgnoreCase(normalized));

        var user = User.builder()
                .email(normalized)
                .passwordHash(passwordEncoder.encode(password))
                .tier(Tier.FREE)
                .admin(isAdmin)
                .createdAt(Instant.now())
                .build();
        userRepository.save(user);

        redisSync.syncLimits(user.getId(), user.getTier());
        log.info("Registered user {} (admin={})", user.getEmail(), user.isAdmin());
        return user;
    }
}
