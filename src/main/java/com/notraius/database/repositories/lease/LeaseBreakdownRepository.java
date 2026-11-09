package com.notraius.database.repositories.lease;

import com.notraius.database.entities.lease.LeaseBreakdown;
import com.notraius.modules.core.common.schedule.SweepKey;
import org.springframework.data.domain.Page;
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
public interface LeaseBreakdownRepository extends JpaRepository<LeaseBreakdown, UUID> {

    @EntityGraph(attributePaths = {"lease", "lease.pricing", "lease.pricing.property", "type", "status"})
    Optional<LeaseBreakdown> findById(UUID breakdownId);

    /**
     * The resolution act's lock: the vigil's consumption and the tenant's own
     * payment serialize on the row's status inside one transaction, so exactly
     * one of them settles the bill (6.3's race law).
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM LeaseBreakdown b WHERE b.id = :breakdownId")
    Optional<LeaseBreakdown> lockById(@Param("breakdownId") UUID breakdownId);

    @EntityGraph(attributePaths = {"type", "status"})
    List<LeaseBreakdown> findByLeaseIdOrderByDeadlineAsc(UUID leaseId);

    @EntityGraph(attributePaths = {"type", "status"})
    Page<LeaseBreakdown> findByLeaseId(UUID leaseId, Pageable pageable);

    /** The calendar's ordering: what the schedule generated, in period order. */
    @EntityGraph(attributePaths = {"type", "status"})
    List<LeaseBreakdown> findByLeaseIdAndPeriodIndexIsNotNullOrderByPeriodIndexAsc(UUID leaseId);

    /**
     * The vigil's bill worklist, one keyset page at a time: due rows ordered (and
     * resumed) by the deadline the resolution reads. Keys, not rows: at ten
     * million bills a page of entities is an out-of-memory, and each resolution
     * owns its transaction and re-reads the row it locks (6.3).
     */
    @Query("""
            SELECT b.id AS id, b.deadline AS sortKey FROM LeaseBreakdown b
            WHERE b.status.code IN :statusCodes
              AND b.deadline < :deadlineBefore
              AND (b.deadline > :afterKey
                   OR (b.deadline = :afterKey AND b.id > :afterId))
            ORDER BY b.deadline ASC, b.id ASC
            """)
    List<SweepKey> findDuePage(@Param("statusCodes") Collection<String> statusCodes,
                               @Param("deadlineBefore") Instant deadlineBefore,
                               @Param("afterKey") Instant afterKey,
                               @Param("afterId") UUID afterId,
                               Pageable pageable);

    @EntityGraph(attributePaths = {"type", "status"})
    @Query("""
            SELECT b FROM LeaseBreakdown b
            WHERE b.lease.id = :leaseId
              AND b.status.code IN :statusCodes
            ORDER BY b.deadline ASC
            """)
    List<LeaseBreakdown> findByLeaseIdAndStatusCodes(@Param("leaseId") UUID leaseId,
                                                     @Param("statusCodes") Collection<String> statusCodes);

    /** The settlement's debts: what is still owed on the calendar, oldest first. */
    @EntityGraph(attributePaths = {"type", "status"})
    @Query("""
            SELECT b FROM LeaseBreakdown b
            WHERE b.lease.id = :leaseId
              AND b.entity.code = :entityCode
              AND b.status.code IN :statusCodes
            ORDER BY b.deadline ASC
            """)
    List<LeaseBreakdown> findOwed(@Param("leaseId") UUID leaseId,
                                  @Param("entityCode") String entityCode,
                                  @Param("statusCodes") Collection<String> statusCodes);

    /**
     * The settlement's tenant-side debt, exactly: the lease's own outstanding
     * money rows plus the repairs <em>the tenant</em> was declared to pay (6.7).
     * The landlord's own repairs are outside the balance sheet by the payer rule.
     */
    @EntityGraph(attributePaths = {"type", "status", "entity"})
    @Query("""
            SELECT b FROM LeaseBreakdown b
            WHERE b.lease.id = :leaseId
              AND b.status.code IN :statusCodes
              AND (b.entity.code = :leaseEntity
                   OR (b.entity.code = :repairEntity AND b.userId = :tenantId))
            ORDER BY b.deadline ASC
            """)
    List<LeaseBreakdown> findTenantOwed(@Param("leaseId") UUID leaseId,
                                        @Param("statusCodes") Collection<String> statusCodes,
                                        @Param("leaseEntity") String leaseEntity,
                                        @Param("repairEntity") String repairEntity,
                                        @Param("tenantId") UUID tenantId);

    /** A one-off charge's row, resolved by its provenance pair (a repair's charge). */
    @EntityGraph(attributePaths = {"type", "status"})
    Optional<LeaseBreakdown> findByEntityCodeAndReferenceId(String entityCode, UUID referenceId);

    boolean existsByLeaseIdAndPeriodIndex(UUID leaseId, Integer periodIndex);

    @Query("SELECT MAX(b.periodIndex) FROM LeaseBreakdown b WHERE b.lease.id = :leaseId")
    Optional<Integer> findMaxPeriodIndex(@Param("leaseId") UUID leaseId);

    @Query("""
            SELECT COUNT(b) FROM LeaseBreakdown b
            WHERE b.lease.id = :leaseId
              AND b.status.code IN :statusCodes
            """)
    long countByLeaseIdAndStatusCodes(@Param("leaseId") UUID leaseId,
                                      @Param("statusCodes") Collection<String> statusCodes);
}
