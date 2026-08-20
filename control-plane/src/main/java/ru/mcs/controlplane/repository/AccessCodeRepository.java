package ru.mcs.controlplane.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mcs.controlplane.domain.AccessCode;

public interface AccessCodeRepository extends JpaRepository<AccessCode, UUID> {

    Optional<AccessCode> findByCode(String code);
}
