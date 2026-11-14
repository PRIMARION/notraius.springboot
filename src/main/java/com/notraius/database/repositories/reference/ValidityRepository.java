package com.notraius.database.repositories.reference;

import com.notraius.database.entities.reference.Validity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ValidityRepository extends JpaRepository<Validity, UUID> {

    /** Market's sweeps: the oldest lapsed bounds first, however the row is scoped. */
    List<Validity> findByToDateBeforeOrderByToDateAsc(Instant now, Pageable pageable);
}
