package com.notraius.database.repositories.lease;

import com.notraius.database.entities.lease.LeaseSettlement;
import com.notraius.modules.core.common.schedule.SweepKey;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeaseSettlementRepository extends JpaRepository<LeaseSettlement, UUID> {

    @EntityGraph(attributePaths = {"lease", "lease.pricing", "lease.pricing.property", "lease.validity",
            "status"})
    Optional<LeaseSettlement> findById(UUID settlementId);

    @EntityGraph(attributePaths = {"lease", "lease.pricing", "lease.pricing.property", "lease.validity", "status"})
    Optional<LeaseSettlement> findByLeaseId(UUID leaseId);

    /**
     * The vigil's overdue worklist, one keyset page at a time: OPEN settlements
     * whose clock has run out, ordered (and resumed) by the deadline the clock
     * reads. Keys rather than rows, so a million overdue settlements cost one
     * page of ids instead of a million hydrated entities — each row is then
     * marked overdue in its own transaction, which re-reads and locks it.
     */
    @Query("""
            SELECT s.id AS id, s.deadline AS sortKey FROM LeaseSettlement s
            WHERE s.status.code = :statusCode
              AND s.deadline < :deadlineBefore
              AND s.lease.status.code IN :leaseStatuses
              AND (s.deadline > :afterKey
                   OR (s.deadline = :afterKey AND s.id > :afterId))
            ORDER BY s.deadline ASC, s.id ASC
            """)
    List<SweepKey> findLapsedPage(@Param("statusCode") String statusCode,
                                  @Param("deadlineBefore") Instant deadlineBefore,
                                  @Param("leaseStatuses") Collection<String> leaseStatuses,
                                  @Param("afterKey") Instant afterKey,
                                  @Param("afterId") UUID afterId,
                                  Pageable pageable);
}
