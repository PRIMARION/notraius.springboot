package com.notraius.database.repositories.visit;

import com.notraius.database.entities.visit.Visit;
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
public interface VisitRepository extends JpaRepository<Visit, UUID> {

    @EntityGraph(attributePaths = {"pricing", "pricing.property", "pricing.property.status", "pricing.type",
            "pricing.status", "validity", "validity.status", "status"})
    Optional<Visit> findById(UUID visitId);

    long countByVisitorIdInAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            Collection<UUID> owners, Instant from, Instant to);

    @Query("SELECT COUNT(v) FROM Visit v WHERE v.visitorId IN :owners " +
            "AND v.status.code IN :statusCodes")
    long countActiveByVisitors(@Param("owners") Collection<UUID> owners,
                               @Param("statusCodes") Collection<String> statusCodes);

    /**
     * The visitor's own page, narrowed by the statuses asked for and by the
     * window the visit is scheduled in.
     * <p>
     * The status list is never empty (the service fills "no filter" with every
     * visit status), so the predicate is a plain {@code IN} the planner can use
     * instead of an {@code OR :statuses IS NULL} that would defeat it. The window
     * filters on the visit's own scheduled window: {@code from} keeps visits that
     * have not ended yet, {@code to} keeps ones that have already begun — so
     * {@code from=now} is "upcoming", and both together is a calendar span.
     * <p>
     * The instants are never null and there is deliberately no
     * {@code :from IS NULL}: Postgres cannot type a null bind parameter inside
     * {@code ? is null}, so "no bound" is stated as the ends of time by the
     * caller. That also keeps both predicates plain ranges the indexes can use.
     */
    // The graph stops where the DTO stops: the pricing id and its property id are
    // the bridges a client follows; the type and the property's content are not read.
    @EntityGraph(attributePaths = {"pricing", "pricing.property", "validity", "status"})
    @Query("""
            SELECT v FROM Visit v
            WHERE v.visitorId = :visitorId
              AND v.status.code IN :statuses
              AND v.validity.toDate >= :from
              AND v.validity.fromDate <= :to
            """)
    Page<Visit> searchAsVisitor(@Param("visitorId") UUID visitorId,
                                @Param("statuses") List<String> statuses,
                                @Param("from") Instant from,
                                @Param("to") Instant to,
                                Pageable pageable);

    /**
     * The counterpart's page: the visits the caller is operationally responsible
     * for — the ones stamped to them, <b>and</b> the ones on properties they own.
     * <p>
     * The owner half is not a courtesy: a paid request is born with no assignee
     * (the stamp is the owner's own act, PLAN 5.2), so a page that read only the
     * assignee column would never show an owner the requests waiting for them.
     * The two columns are indexed separately, so the {@code OR} is a bitmap union
     * rather than a scan, and the status and window filters keep it to what the
     * caller asked for.
     */
    // The graph stops where the DTO stops: the pricing id and its property id are
    // the bridges a client follows; the type and the property's content are not read.
    @EntityGraph(attributePaths = {"pricing", "pricing.property", "validity", "status"})
    @Query("""
            SELECT v FROM Visit v
            WHERE (v.userId = :userId OR v.ownerId = :userId)
              AND v.status.code IN :statuses
              AND v.validity.toDate >= :from
              AND v.validity.fromDate <= :to
            """)
    Page<Visit> searchAsParty(@Param("userId") UUID userId,
                              @Param("statuses") List<String> statuses,
                              @Param("from") Instant from,
                              @Param("to") Instant to,
                              Pageable pageable);

    /**
     * The expiry sweep's worklist, one keyset page at a time: rows in the given
     * statuses created before a fence and not older than the sweep's lookback.
     * The fence is the clock, and the clock is what expires a visit; the
     * {@code (createdAt, id)} keyset is what lets a pass resume after the last
     * row the previous pass settled rather than re-reading the head.
     */
    @Query("""
            SELECT v.id AS id, v.createdAt AS sortKey FROM Visit v
            WHERE v.status.code IN :statusCodes
              AND v.createdAt < :createdBefore
              AND v.createdAt >= :createdAfter
              AND (v.createdAt > :afterKey
                   OR (v.createdAt = :afterKey AND v.id > :afterId))
            ORDER BY v.createdAt ASC, v.id ASC
            """)
    List<SweepKey> findStalePage(@Param("statusCodes") Collection<String> statusCodes,
                                 @Param("createdBefore") Instant createdBefore,
                                 @Param("createdAfter") Instant createdAfter,
                                 @Param("afterKey") Instant afterKey,
                                 @Param("afterId") UUID afterId,
                                 Pageable pageable);

    /**
     * 5.3's lapsed claims, one keyset page at a time: a STARTED visit whose
     * countersignature never came inside the window. The claim instant — not the
     * creation instant — is the clock here, because the window opens when
     * somebody claims the visit is happening.
     */
    @Query("""
            SELECT v.id AS id, v.startedAt AS sortKey FROM Visit v
            WHERE v.status.code = :statusCode
              AND v.startedAt IS NOT NULL
              AND v.startConfirmedAt IS NULL
              AND v.startedAt < :claimBefore
              AND (v.startedAt > :afterKey
                   OR (v.startedAt = :afterKey AND v.id > :afterId))
            ORDER BY v.startedAt ASC, v.id ASC
            """)
    List<SweepKey> findLapsedClaimPage(@Param("statusCode") String statusCode,
                                       @Param("claimBefore") Instant claimBefore,
                                       @Param("afterKey") Instant afterKey,
                                       @Param("afterId") UUID afterId,
                                       Pageable pageable);

    /**
     * 5.3's silent visits, one keyset page at a time: a CONFIRMED visit whose
     * planned end passed with nobody claiming it started. Nobody claims it
     * happened, so nothing was billed.
     */
    @Query("""
            SELECT v.id AS id, v.validity.toDate AS sortKey FROM Visit v
            WHERE v.status.code = :statusCode
              AND v.startedAt IS NULL
              AND v.validity.toDate < :now
              AND (v.validity.toDate > :afterKey
                   OR (v.validity.toDate = :afterKey AND v.id > :afterId))
            ORDER BY v.validity.toDate ASC, v.id ASC
            """)
    List<SweepKey> findUnclaimedPastEndPage(@Param("statusCode") String statusCode,
                                            @Param("now") Instant now,
                                            @Param("afterKey") Instant afterKey,
                                            @Param("afterId") UUID afterId,
                                            Pageable pageable);

    /**
     * 5.3's hour, one keyset page at a time: a confirmed start whose assessment
     * hour has run out. Silence neither holds the money hostage nor rescues it
     * from release.
     */
    @Query("""
            SELECT v.id AS id, v.validity.toDate AS sortKey FROM Visit v
            WHERE v.status.code = :statusCode
              AND v.startConfirmedAt IS NOT NULL
              AND v.validity.toDate < :hourStartedBefore
              AND (v.validity.toDate > :afterKey
                   OR (v.validity.toDate = :afterKey AND v.id > :afterId))
            ORDER BY v.validity.toDate ASC, v.id ASC
            """)
    List<SweepKey> findAssessmentHourPassedPage(@Param("statusCode") String statusCode,
                                                @Param("hourStartedBefore") Instant hourStartedBefore,
                                                @Param("afterKey") Instant afterKey,
                                                @Param("afterId") UUID afterId,
                                                Pageable pageable);

    /** Pay's payment id → the visit it paid for (the sweep's re-read, reversed). */
    Optional<Visit> findByPaymentId(UUID paymentId);

    /** The visits a pricing has sold — the pricing's in-use gate. */
    boolean existsByPricingId(UUID pricingId);

    /**
     * The property's delete gate: a visit still in flight keeps the property
     * alive, because money is held against a listing that must keep resolving.
     */
    @Query("""
            SELECT COUNT(v) FROM Visit v
            WHERE v.pricing.property.id = :propertyId
              AND v.status.code IN :statusCodes
            """)
    long countLiveForProperty(@Param("propertyId") UUID propertyId,
                              @Param("statusCodes") Collection<String> statusCodes);

    /**
     * The property's wind-down (PLAN 5.4): every visit the listing still owes
     * money for. An unpublished property must not leave held custody against a
     * page nobody can see.
     */
    @EntityGraph(attributePaths = {"pricing", "pricing.property", "validity", "status"})
    @Query("""
            SELECT v FROM Visit v
            WHERE v.pricing.property.id = :propertyId
              AND v.status.code IN :statusCodes
            """)
    List<Visit> findLiveForProperty(@Param("propertyId") UUID propertyId,
                                    @Param("statusCodes") Collection<String> statusCodes);

    /**
     * The retry's law (PLAN 5.1): an unpaid request of this visitor for this
     * price is adopted, not duplicated — so the second click carries the same
     * already-persisted reference and pay's dedupe converges it.
     */
    @EntityGraph(attributePaths = {"pricing", "pricing.property", "pricing.type", "validity", "status"})
    @Query("""
            SELECT v FROM Visit v
            WHERE v.visitorId = :visitorId
              AND v.pricing.id = :pricingId
              AND v.status.code = :statusCode
            ORDER BY v.createdAt DESC
            """)
    List<Visit> findReusable(@Param("visitorId") UUID visitorId,
                             @Param("pricingId") UUID pricingId,
                             @Param("statusCode") String statusCode);
}
