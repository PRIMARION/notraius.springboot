package com.notraius.database.repositories.lease;

import com.notraius.database.entities.lease.LeaseCondition;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeaseConditionRepository extends JpaRepository<LeaseCondition, UUID> {

    @EntityGraph(attributePaths = {"lease", "lease.pricing", "lease.pricing.property", "type", "status"})
    Optional<LeaseCondition> findById(UUID conditionId);

    @EntityGraph(attributePaths = {"type", "status"})
    @Query("""
            SELECT c FROM LeaseCondition c
            WHERE c.lease.id = :leaseId
              AND c.type.code = :typeCode
            ORDER BY c.createdAt ASC
            """)
    List<LeaseCondition> findByLeaseAndType(@Param("leaseId") UUID leaseId,
                                            @Param("typeCode") String typeCode);

    @EntityGraph(attributePaths = {"type", "status"})
    List<LeaseCondition> findByLeaseIdOrderByCreatedAtAsc(UUID leaseId);

    @Query("""
            SELECT COUNT(c) FROM LeaseCondition c
            WHERE c.lease.id = :leaseId
              AND c.type.code = :typeCode
              AND c.status.code IN :statusCodes
            """)
    long countByLeaseAndTypeAndStatus(@Param("leaseId") UUID leaseId,
                                      @Param("typeCode") String typeCode,
                                      @Param("statusCodes") Collection<String> statusCodes);
}
