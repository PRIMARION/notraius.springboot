package com.notraius.database.repositories.reference;

import com.notraius.database.entities.reference.Validity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ValidityRepository extends JpaRepository<Validity, UUID> {
}
