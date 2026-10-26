package com.notraius.common.services;

import com.notraius.database.entities.reference.Status;
import com.notraius.database.entities.reference.Validity;
import com.notraius.database.repositories.reference.ValidityRepository;
import org.primarion.database.entities.lifecycle.ValidityPort;
import org.primarion.database.entities.lifecycle.ValidityView;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Notraius-side adapter for the shared {@link ValidityPort} contract. Shared
 * modules (event, subscription) create and read validity rows in the
 * <b>host's own table</b> — {@code notraius.validity} here — so no redundant
 * module-local validity entity/table exists anywhere in the ecosystem.
 * <p>
 * Statuses are entity-scoped in the notraius registry: creation resolves the
 * status under the caller-supplied entity code (e.g. {@code EVENTS},
 * {@code SUBSCRIPTIONS}); transitions resolve within the same entity as the
 * row's current status.
 */
@Component
public class NotraiusValidityAdapter implements ValidityPort {

    private final ValidityRepository validityRepository;
    private final StatusResolver statusResolver;

    public NotraiusValidityAdapter(ValidityRepository validityRepository,
                                   StatusResolver statusResolver) {
        this.validityRepository = validityRepository;
        this.statusResolver = statusResolver;
    }

    @Override
    @Transactional
    public UUID create(String entityCode, String statusCode, Instant fromDate, Instant toDate) {
        if (fromDate == null) {
            throw new IllegalArgumentException("Validity fromDate is required");
        }
        Validity validity = new Validity();
        validity.setStatus(statusResolver.resolve(entityCode, statusCode));
        validity.setFromDate(fromDate);
        validity.setToDate(toDate);
        return validityRepository.save(validity).getId();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ValidityView> find(UUID validityId) {
        return validityRepository.findById(validityId)
                .map(v -> new ValidityView(v.getId(),
                        v.getStatus() != null ? v.getStatus().getCode() : null,
                        v.getFromDate(), v.getToDate()));
    }

    @Override
    @Transactional
    public void updateWindow(UUID validityId, Instant fromDate, Instant toDate) {
        Validity validity = validityRepository.findById(validityId)
                .orElseThrow(() -> new IllegalArgumentException("Validity not found: " + validityId));
        if (fromDate != null) {
            validity.setFromDate(fromDate);
        }
        validity.setToDate(toDate);
        validityRepository.save(validity);
    }

    @Override
    @Transactional
    public void updateStatus(UUID validityId, String statusCode) {
        Validity validity = validityRepository.findById(validityId)
                .orElseThrow(() -> new IllegalArgumentException("Validity not found: " + validityId));
        String entityCode = validity.getStatus() != null && validity.getStatus().getEntity() != null
                ? validity.getStatus().getEntity().getCode()
                : null;
        if (entityCode == null) {
            throw new IllegalStateException("Validity " + validityId + " has no entity-scoped status");
        }
        validity.setStatus(statusResolver.resolve(entityCode, statusCode));
        validityRepository.save(validity);
    }
}
