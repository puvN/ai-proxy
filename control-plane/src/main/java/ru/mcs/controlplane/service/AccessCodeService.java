package ru.mcs.controlplane.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.mcs.controlplane.domain.AccessCode;
import ru.mcs.controlplane.domain.CodeStatus;
import ru.mcs.controlplane.domain.Subscription;
import ru.mcs.controlplane.domain.SubscriptionSource;
import ru.mcs.controlplane.domain.Tier;
import ru.mcs.controlplane.repository.AccessCodeRepository;
import ru.mcs.controlplane.repository.SubscriptionRepository;
import ru.mcs.controlplane.repository.UserRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccessCodeService {

    private final AccessCodeRepository accessCodeRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final RedisGatewaySyncService redisSync;

    @Transactional
    public List<String> generate(int count, Tier tier, Instant expiresAt) {
        if (count < 1 || count > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Count must be between 1 and 1000");
        }

        var codes = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            var code = KeyHasher.generateAccessCode();
            var entity = AccessCode.builder()
                    .code(code)
                    .tier(tier)
                    .status(CodeStatus.NEW)
                    .expiresAt(expiresAt)
                    .createdAt(Instant.now())
                    .build();
            accessCodeRepository.save(entity);
            codes.add(code);
        }
        log.info("Generated {} access codes (tier={})", count, tier);
        return codes;
    }

    @Transactional(readOnly = true)
    public List<AccessCode> listCodes() {
        return accessCodeRepository.findAll();
    }

    @Transactional
    public Tier activate(UUID userId, String code) {
        var accessCode = accessCodeRepository.findByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invalid code"));

        if (accessCode.getStatus() != CodeStatus.NEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Code already used");
        }
        if (accessCode.getExpiresAt() != null && accessCode.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "Code expired");
        }

        accessCode.setStatus(CodeStatus.CLAIMED);
        accessCode.setClaimedBy(userId);
        accessCode.setClaimedAt(Instant.now());
        accessCodeRepository.save(accessCode);

        var subscription = Subscription.builder()
                .userId(userId)
                .tier(accessCode.getTier())
                .startedAt(Instant.now())
                .source(SubscriptionSource.CODE)
                .build();
        subscriptionRepository.save(subscription);

        var user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        user.setTier(accessCode.getTier());
        userRepository.save(user);

        redisSync.syncLimits(userId, user.getTier());
        log.info("User {} activated code, tier -> {}", userId, user.getTier());
        return user.getTier();
    }
}
