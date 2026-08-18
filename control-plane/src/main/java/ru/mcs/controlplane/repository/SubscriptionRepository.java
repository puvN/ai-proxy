package ru.mcs.controlplane.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mcs.controlplane.domain.Subscription;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {
}
