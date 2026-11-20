package com.notraius.database.repositories.lease;

import com.notraius.database.entities.lease.SweepCursor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SweepCursorRepository extends JpaRepository<SweepCursor, UUID> {

    /** One ledger row per job name (V36's unique constraint). */
    Optional<SweepCursor> findByJob(String job);
}
