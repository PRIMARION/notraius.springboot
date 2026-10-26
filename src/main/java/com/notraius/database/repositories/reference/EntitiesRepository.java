package com.notraius.database.repositories.reference;

import com.notraius.database.entities.reference.Entities;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EntitiesRepository extends JpaRepository<Entities, UUID> {

    Optional<Entities> findByCode(String code);
}