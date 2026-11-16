package com.notraius.database.repositories.reference;

import com.notraius.database.entities.reference.Duration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface DurationRepository extends JpaRepository<Duration, UUID> {

    /*
     * Every finder below carries an entity graph: a duration page always shows
     * the unit's code and the row's status, and without the graph each of those
     * EAGER associations costs one extra select per row.
     */

    @EntityGraph(attributePaths = {"type", "status"})
    Page<Duration> findByTypeId(UUID typeId, Pageable pageable);

    @EntityGraph(attributePaths = {"type", "status"})
    Page<Duration> findByTypeIdAndStatusId(UUID typeId, UUID statusId, Pageable pageable);

    @EntityGraph(attributePaths = {"type", "status"})
    Page<Duration> findByStatusId(UUID statusId, Pageable pageable);

    /** (type, value) identity — refused on both create and update. */
    long countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(Instant from, Instant to);

    long countByStatus_Code(String statusCode);

    boolean existsByTypeIdAndValue(UUID typeId, Integer value);

    boolean existsByTypeIdAndValueAndIdNot(UUID typeId, Integer value, UUID id);

    /** The type's delete gate: a duration pointing at it keeps it alive. */
    boolean existsByTypeId(UUID typeId);
}
