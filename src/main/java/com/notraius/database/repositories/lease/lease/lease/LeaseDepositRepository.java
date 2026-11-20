package com.notraius.database.repositories.lease.lease.lease;

import com.notraius.database.entities.lease.lease.lease.LeaseDeposit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeaseDepositRepository extends JpaRepository<LeaseDeposit, UUID> {

    @EntityGraph(attributePaths = {"lease", "policy", "policy.type", "type", "status"})
    List<LeaseDeposit> findByLeaseIdOrderByPolicyCodeAsc(UUID leaseId);

    @EntityGraph(attributePaths = {"lease", "policy", "type", "status"})
    Optional<LeaseDeposit> findByLeaseIdAndPolicyId(UUID leaseId, UUID policyId);
}
