package com.notraius.database.repositories.lease.lease.lease;

import com.notraius.database.entities.lease.lease.lease.LeaseRepair;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeaseRepairRepository extends JpaRepository<LeaseRepair, UUID> {

    @EntityGraph(attributePaths = {"lease", "lease.pricing", "lease.pricing.property", "condition", "status"})
    Optional<LeaseRepair> findById(UUID repairId);

    @EntityGraph(attributePaths = {"status"})
    List<LeaseRepair> findByLeaseIdOrderByCreatedAtAsc(UUID leaseId);

    @EntityGraph(attributePaths = {"status"})
    List<LeaseRepair> findByConditionIdOrderByCreatedAtAsc(UUID conditionId);

    long countByLeaseId(UUID leaseId);
}
