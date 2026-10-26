package com.notraius.common.services;

import com.notraius.database.entities.reference.Entities;
import com.notraius.database.entities.reference.Status;
import com.notraius.database.entities.reference.Validity;
import com.notraius.database.repositories.reference.EntitiesRepository;
import com.notraius.database.repositories.reference.StatusRepository;
import com.notraius.database.repositories.reference.ValidityRepository;
import org.primarion.module.errors.CommonErrors;
import org.primarion.module.errors.NotFoundException;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class StatusResolver {

    private final EntitiesRepository entitiesRepository;
    private final StatusRepository statusRepository;
    private final ValidityRepository validityRepository;

    public StatusResolver(EntitiesRepository entitiesRepository, StatusRepository statusRepository,
                          ValidityRepository validityRepository) {
        this.entitiesRepository = entitiesRepository;
        this.statusRepository = statusRepository;
        this.validityRepository = validityRepository;
    }

    public Status resolve(String entityCode, String statusCode) {
        Entities entity = entitiesRepository.findByCode(entityCode)
                .orElseThrow(() -> new NotFoundException(CommonErrors.NOT_FOUND, "Entity not found: " + entityCode));
        return statusRepository.findByCodeAndEntityId(statusCode, entity.getId())
                .orElseThrow(() -> new NotFoundException(CommonErrors.NOT_FOUND, "Status not found: " + statusCode));
    }

    public Status resolveSafe(String entityCode, String statusCode) {
        return entitiesRepository.findByCode(entityCode)
                .flatMap(e -> statusRepository.findByCodeAndEntityId(statusCode, e.getId()))
                .orElse(null);
    }

    public Entities resolveEntity(String entityCode) {
        return entitiesRepository.findByCode(entityCode)
                .orElseThrow(() -> new NotFoundException(CommonErrors.NOT_FOUND, "Entity not found: " + entityCode));
    }

    // ── #EA-08: never-null validity model ────────────────────────────────

    /**
     * Opens a validity window for the given entity status NOW. The validity
     * row is persisted immediately — the owning row is born with a non-null
     * validity, doctrine-enforced.
     */
    public Validity openValidity(String entityCode, String statusCode) {
        return openValidity(entityCode, statusCode, Instant.now());
    }

    /** Opens a validity window for the given entity status from a fixed instant. */
    public Validity openValidity(String entityCode, String statusCode, Instant from) {
        Validity validity = new Validity();
        validity.setStatus(resolve(entityCode, statusCode));
        validity.setFromDate(from);
        return validityRepository.save(validity);
    }

    /**
     * Transitions a validity: same row, new status. The from/to window is
     * preserved; callers may adjust it before saving.
     */
    public Validity transition(Validity validity, String entityCode, String statusCode) {
        validity.setStatus(resolve(entityCode, statusCode));
        return validity;
    }
}
