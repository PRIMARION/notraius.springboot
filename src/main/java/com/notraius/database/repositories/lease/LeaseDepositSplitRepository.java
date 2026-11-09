package com.notraius.database.repositories.lease;

import com.notraius.database.entities.lease.LeaseDepositSplit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LeaseDepositSplitRepository extends JpaRepository<LeaseDepositSplit, UUID> {

    /**
     * The FIFO walk's read (6.3): every split of this deposit/lease in birth
     * order, so "the oldest still-HELD advance escrow" is one ordered scan and
     * never an interpretation.
     */
    @EntityGraph(attributePaths = {"deposit", "deposit.policy", "status"})
    List<LeaseDepositSplit> findByLeaseIdOrderBySequenceAsc(UUID leaseId);

    List<LeaseDepositSplit> findByLeaseIdAndStatusCodeOrderBySequenceAsc(UUID leaseId, String statusCode);

    long countByLeaseIdAndStatusCode(UUID leaseId, String statusCode);
}
