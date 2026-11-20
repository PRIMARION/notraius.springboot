package com.notraius.database.repositories.lease.lease.records;

import com.notraius.database.entities.lease.lease.records.LeaseNotice;
import com.notraius.modules.core.core.common.schedule.sweep.SweepKey;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeaseNoticeRepository extends JpaRepository<LeaseNotice, UUID> {

    @EntityGraph(attributePaths = {"lease", "lease.pricing", "lease.pricing.property", "lease.validity",
            "type", "validity", "validity.status"})
    Optional<LeaseNotice> findById(UUID noticeId);

    @EntityGraph(attributePaths = {"type", "validity", "validity.status"})
    List<LeaseNotice> findByLeaseIdOrderByCreatedAtDesc(UUID leaseId);

    /**
     * The engine's live notices: unanswered, and their own clock still running.
     * The clock is the validity's end, never a column of the notice's own.
     */
    @EntityGraph(attributePaths = {"lease", "lease.pricing", "lease.pricing.property", "lease.validity",
            "type", "validity"})
    @Query("""
            SELECT n FROM LeaseNotice n
            WHERE n.lease.id = :leaseId
              AND n.response IS NULL
              AND n.validity.toDate > :now
            ORDER BY n.createdAt ASC
            """)
    List<LeaseNotice> findLive(@Param("leaseId") UUID leaseId, @Param("now") Instant now);

    /**
     * The vigil's worklist, one keyset page at a time: unanswered notices whose
     * window has run out, ordered (and resumed) by the window's own end — the
     * clock the notice is judged by.
     */
    @Query("""
            SELECT n.id AS id, n.validity.toDate AS sortKey FROM LeaseNotice n
            WHERE n.response IS NULL
              AND n.validity.toDate <= :now
              AND n.validity.status.code = :validityStatus
              AND n.lease.status.code IN :leaseStatuses
              AND (n.validity.toDate > :afterKey
                   OR (n.validity.toDate = :afterKey AND n.id > :afterId))
            ORDER BY n.validity.toDate ASC, n.id ASC
            """)
    List<SweepKey> findLapsedPage(@Param("now") Instant now,
                                  @Param("validityStatus") String validityStatus,
                                  @Param("leaseStatuses") java.util.Collection<String> leaseStatuses,
                                  @Param("afterKey") Instant afterKey,
                                  @Param("afterId") UUID afterId,
                                  Pageable pageable);

    /**
     * The grace that ran out, one keyset page at a time: accepted notices whose
     * window has closed. The acceptance bought time, and the time is spent —
     * escalation follows unless the balance meanwhile reached the standard (6.5).
     */
    @Query("""
            SELECT n.id AS id, n.validity.toDate AS sortKey FROM LeaseNotice n
            WHERE n.response = :response
              AND n.validity.toDate <= :now
              AND n.validity.status.code = :validityStatus
              AND n.lease.status.code IN :leaseStatuses
              AND (n.validity.toDate > :afterKey
                   OR (n.validity.toDate = :afterKey AND n.id > :afterId))
            ORDER BY n.validity.toDate ASC, n.id ASC
            """)
    List<SweepKey> findAnsweredLapsedPage(@Param("response") String response,
                                          @Param("now") Instant now,
                                          @Param("validityStatus") String validityStatus,
                                          @Param("leaseStatuses") java.util.Collection<String> leaseStatuses,
                                          @Param("afterKey") Instant afterKey,
                                          @Param("afterId") UUID afterId,
                                          Pageable pageable);

    /**
     * Issued notices of one kind across leases — the reconversion sweep's read,
     * one keyset page at a time, ordered (and resumed) by the notice's own
     * creation instant.
     */
    @Query("""
            SELECT n.id AS id, n.createdAt AS sortKey FROM LeaseNotice n
            WHERE n.type.code = :typeCode
              AND n.response IS NULL
              AND n.validity.status.code = :validityStatus
              AND n.lease.status.code = :leaseStatus
              AND (n.createdAt > :afterKey
                   OR (n.createdAt = :afterKey AND n.id > :afterId))
            ORDER BY n.createdAt ASC, n.id ASC
            """)
    List<SweepKey> findByTypeAndLeaseStatusPage(@Param("typeCode") String typeCode,
                                                @Param("validityStatus") String validityStatus,
                                                @Param("leaseStatus") String leaseStatus,
                                                @Param("afterKey") Instant afterKey,
                                                @Param("afterId") UUID afterId,
                                                Pageable pageable);

    /** An open notice of one kind: a second deposit warning never stacks on the first. */
    @EntityGraph(attributePaths = {"type", "validity"})
    @Query("""
            SELECT n FROM LeaseNotice n
            WHERE n.lease.id = :leaseId
              AND n.type.code = :typeCode
              AND n.response IS NULL
              AND n.validity.toDate > :now
            ORDER BY n.createdAt DESC
            """)
    List<LeaseNotice> findOpenOfType(@Param("leaseId") UUID leaseId,
                                     @Param("typeCode") String typeCode,
                                     @Param("now") Instant now);
}
