package com.notraius.database.repositories.lease;

import com.notraius.database.entities.lease.Lease;
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
public interface LeaseRepository extends JpaRepository<Lease, UUID> {

    @EntityGraph(attributePaths = {"pricing", "pricing.property", "pricing.property.status", "pricing.type",
            "pricing.duration", "pricing.duration.type", "validity", "validity.status", "status"})
    Optional<Lease> findById(UUID leaseId);

    /**
     * The tenant's own page, narrowable to a status set and to a term window.
     * <p>
     * The status list is never empty (the service fills "no filter" with every
     * lease status), so the predicate is a plain {@code IN} the planner can use.
     * The window filters on the lease's own term: {@code from} keeps tenancies
     * that have not ended yet, {@code to} keeps ones that have already begun —
     * so {@code from=now} is "current and future", and both together is a span.
     * <p>
     * The instants are never null and there is deliberately no
     * {@code :from IS NULL}: Postgres cannot type a null bind inside
     * {@code ? is null}, so "no bound" is stated as the ends of time.
     */
    @EntityGraph(attributePaths = {"pricing", "pricing.property", "validity", "status"})
    @Query("""
            SELECT l FROM Lease l
            WHERE l.userId = :tenantId
              AND l.status.code IN :statuses
              AND l.validity.toDate >= :from
              AND l.validity.fromDate <= :to
            """)
    Page<Lease> searchAsTenant(@Param("tenantId") UUID tenantId,
                               @Param("statuses") List<String> statuses,
                               @Param("from") Instant from,
                               @Param("to") Instant to,
                               Pageable pageable);

    /** The beneficiary's page — the leases born from properties this caller owned. */
    @EntityGraph(attributePaths = {"pricing", "pricing.property", "validity", "status"})
    @Query("""
            SELECT l FROM Lease l
            WHERE l.ownerId = :ownerId
              AND l.status.code IN :statuses
              AND l.validity.toDate >= :from
              AND l.validity.fromDate <= :to
            """)
    Page<Lease> searchAsOwner(@Param("ownerId") UUID ownerId,
                              @Param("statuses") List<String> statuses,
                              @Param("from") Instant from,
                              @Param("to") Instant to,
                              Pageable pageable);

    /**
     * The retry's law (6.1): a lease this tenant already started on this pricing
     * is adopted, not duplicated — its own id is the funding reference, so pay's
     * (reference, currency) dedupe folds the retry into the funding that stands.
     */
    @EntityGraph(attributePaths = {"pricing", "pricing.property", "pricing.duration", "pricing.duration.type",
            "validity", "status"})
    @Query("""
            SELECT l FROM Lease l
            WHERE l.userId = :tenantId
              AND l.pricing.id = :pricingId
              AND l.status.code IN :statusCodes
            ORDER BY l.createdAt DESC
            """)
    List<Lease> findReusable(@Param("tenantId") UUID tenantId,
                             @Param("pricingId") UUID pricingId,
                             @Param("statusCodes") Collection<String> statusCodes);

    /**
     * The staleness sweep's worklist, one keyset page at a time: PENDING leases
     * whose window has run out. Keys, not rows, because every expiry runs in its
     * own transaction: a row that fails half-way must roll back alone instead of
     * poisoning the sweep's persistence context — and because ten million rows
     * are ten million objects if the worklist returns entities.
     * <p>
     * The two absolute bounds are the flush horizon: older than
     * {@code createdBefore} is stale; older than {@code createdAfter} is beyond
     * the sweep's lookback and is a human's to look at, so a long outage cannot
     * make one tick walk the whole table. The third bound is the cursor: rows
     * strictly after {@code (afterKey, afterId)} in {@code (createdAt, id)}
     * order, so a new pass resumes after the last row the previous one settled
     * instead of re-reading the head of the list.
     */
    @Query("""
            SELECT l.id AS id, l.createdAt AS sortKey FROM Lease l
            WHERE l.status.code = :statusCode
              AND l.createdAt < :createdBefore
              AND l.createdAt >= :createdAfter
              AND (l.createdAt > :afterKey
                   OR (l.createdAt = :afterKey AND l.id > :afterId))
            ORDER BY l.createdAt ASC, l.id ASC
            """)
    List<SweepKey> findStalePage(@Param("statusCode") String statusCode,
                                 @Param("createdBefore") Instant createdBefore,
                                 @Param("createdAfter") Instant createdAfter,
                                 @Param("afterKey") Instant afterKey,
                                 @Param("afterId") UUID afterId,
                                 Pageable pageable);

    /**
     * The property's live tenancies — the delete gate and the one-live-lease
     * invariant (6.0). A lease holds a listing's money and its occupancy, so the
     * row must keep resolving for as long as any of these statuses stands.
     */
    @EntityGraph(attributePaths = {"pricing", "pricing.property", "validity", "status"})
    @Query("""
            SELECT l FROM Lease l
            WHERE l.pricing.property.id = :propertyId
              AND l.status.code IN :statusCodes
            """)
    List<Lease> findLiveForProperty(@Param("propertyId") UUID propertyId,
                                    @Param("statusCodes") Collection<String> statusCodes);

    @Query("""
            SELECT COUNT(l) FROM Lease l
            WHERE l.pricing.property.id = :propertyId
              AND l.status.code IN :statusCodes
            """)
    long countLiveForProperty(@Param("propertyId") UUID propertyId,
                              @Param("statusCodes") Collection<String> statusCodes);

    /** The pricing's in-use gate: history reads, but a sold price stops answering. */
    boolean existsByPricingId(UUID pricingId);

    /**
     * The vigil's end-of-life worklist, one keyset page at a time: ACTIVE
     * tenancies whose validity has ended, ordered (and resumed) by the validity's
     * own end — the instant the tenancy is over.
     */
    @Query("""
            SELECT l.id AS id, l.validity.toDate AS sortKey FROM Lease l
            WHERE l.status.code = :statusCode
              AND l.validity.toDate IS NOT NULL
              AND l.validity.toDate <= :now
              AND (l.validity.toDate > :afterKey
                   OR (l.validity.toDate = :afterKey AND l.id > :afterId))
            ORDER BY l.validity.toDate ASC, l.id ASC
            """)
    List<SweepKey> findEndedActivePage(@Param("statusCode") String statusCode,
                                       @Param("now") Instant now,
                                       @Param("afterKey") Instant afterKey,
                                       @Param("afterId") UUID afterId,
                                       Pageable pageable);

    /**
     * The rolling horizon's worklist, one keyset page at a time: open-ended
     * ACTIVE tenancies, oldest first. The page order is {@code createdAt} — the
     * horizon is a function of when the tenancy began, and the oldest tenancy is
     * the one whose calendar most needs extending.
     */
    @Query("""
            SELECT l.id AS id, l.createdAt AS sortKey FROM Lease l
            WHERE l.status.code = :statusCode
              AND l.validity.toDate IS NULL
              AND (l.createdAt > :afterKey
                   OR (l.createdAt = :afterKey AND l.id > :afterId))
            ORDER BY l.createdAt ASC, l.id ASC
            """)
    List<SweepKey> findOpenEndedActivePage(@Param("statusCode") String statusCode,
                                           @Param("afterKey") Instant afterKey,
                                           @Param("afterId") UUID afterId,
                                           Pageable pageable);
}
