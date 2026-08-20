package ru.mcs.controlplane.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.mcs.controlplane.domain.ApiKey;
import ru.mcs.controlplane.dto.CreatedKey;
import ru.mcs.controlplane.repository.ApiKeyRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final RedisGatewaySyncService redisSync;

    @Transactional
    public CreatedKey createKey(UUID userId) {
        var raw = KeyHasher.generateApiKey();
        var hash = KeyHasher.sha256(raw);

        var entity = ApiKey.builder()
                .userId(userId)
                .keyHash(hash)
                .keyPrefix(raw.substring(0, 12))
                .createdAt(Instant.now())
                .build();
        apiKeyRepository.save(entity);

        redisSync.syncKey(userId, hash);
        log.info("Created API key for user {}", userId);
        return new CreatedKey(raw, entity);
    }

    @Transactional(readOnly = true)
    public List<ApiKey> list(UUID userId) {
        return apiKeyRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public void revoke(UUID userId, UUID keyId) {
        var key = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Key not found"));
        if (!key.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your key");
        }
        if (key.getRevokedAt() == null) {
            key.setRevokedAt(Instant.now());
            apiKeyRepository.save(key);
            redisSync.removeKey(key.getKeyHash());
            log.info("Revoked API key {} for user {}", keyId, userId);
        }
    }
}
