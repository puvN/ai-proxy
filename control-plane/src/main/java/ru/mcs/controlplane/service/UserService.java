package ru.mcs.controlplane.service;

import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.mcs.controlplane.domain.Tier;
import ru.mcs.controlplane.domain.User;
import ru.mcs.controlplane.repository.UserRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RedisGatewaySyncService redisSync;

    public User getByEmail(String email) {
        return userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    public User getCurrent(Authentication auth) {
        return getByEmail(auth.getName());
    }

    public List<User> list() {
        return userRepository.findAll();
    }

    @Transactional
    public User changeTier(UUID userId, Tier tier) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        user.setTier(tier);
        userRepository.save(user);
        redisSync.syncLimits(userId, tier);
        log.info("Admin changed user {} tier -> {}", userId, tier);
        return user;
    }
}
