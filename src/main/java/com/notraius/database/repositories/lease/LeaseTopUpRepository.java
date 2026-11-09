package com.notraius.database.repositories.lease;

import com.notraius.database.entities.lease.LeaseTopUp;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeaseTopUpRepository extends JpaRepository<LeaseTopUp, UUID> {

    @EntityGraph(attributePaths = {"lease", "lease.pricing", "lease.pricing.property", "deposit", "status"})
    Optional<LeaseTopUp> findById(UUID topUpId);

    @EntityGraph(attributePaths = {"deposit", "status"})
    List<LeaseTopUp> findByLeaseIdOrderByCreatedAtDesc(UUID leaseId);
}
