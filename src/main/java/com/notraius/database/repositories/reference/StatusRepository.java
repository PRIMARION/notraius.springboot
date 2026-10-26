package com.notraius.database.repositories.reference;

import com.notraius.database.entities.reference.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StatusRepository extends JpaRepository<Status, UUID> {

    Optional<Status> findByCode(String code);

    List<Status> findByEntityId(UUID entityId);

    Optional<Status> findByCodeAndEntityId(String code, UUID entityId);

    List<Status> findByEntityCode(String entityCode);

    Optional<Status> findByCodeAndEntityCode(String code, String entityCode);

    List<Status> findByDescriptionContainingIgnoreCase(String description);

    List<Status> findByCodeContainingIgnoreCase(String code);

    boolean existsByCode(String code);

    boolean existsByCodeAndEntityId(String code, UUID entityId);

    long countByCode(String code);

    long countByEntityId(UUID entityId);

    long countByCodeAndEntityId(String code, UUID entityId);

    long countByEntityCode(String entityCode);

    @Query("SELECT s FROM Status s WHERE s.code LIKE %:search% OR s.description LIKE %:search%")
    List<Status> search(@Param("search") String search);

    List<Status> findAllByOrderByCodeAsc();

    List<Status> findAllByOrderByCodeDesc();

    List<Status> findByEntityIdOrderByCodeAsc(UUID entityId);

    List<Status> findByEntityIdOrderByCodeDesc(UUID entityId);
}
