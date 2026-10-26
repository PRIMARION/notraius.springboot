package com.notraius.database.repositories.reference;

import com.notraius.database.entities.reference.CommunicationRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CommunicationRoleRepository extends JpaRepository<CommunicationRole, UUID> {

    Optional<CommunicationRole> findByCode(String code);

    Optional<CommunicationRole> findByCodeAndEntityId(String code, UUID entityId);
}
