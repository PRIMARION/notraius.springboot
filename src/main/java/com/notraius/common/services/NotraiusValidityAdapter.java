package com.notraius.common.services;

import com.notraius.database.entities.reference.Status;
import com.notraius.database.entities.reference.Validity;
import com.notraius.database.repositories.reference.ValidityRepository;
import org.primarion.database.entities.lifecycle.ValidityPort;
import org.primarion.database.entities.lifecycle.ValidityView;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        return validityRepository.findById(validityId).map(NotraiusValidityAdapter::viewOf);
    }

    /**
     * The batch read the shared schedulers use — <b>one query, not one per row</b>.
     * <p>
     * {@link ValidityPort#findMany} ships a default that loops {@link #find}, and
     * that default is a trap in a scheduler: the event scan asks once per tick for
     * the window of every due event (100 by default, every second), so the loop is
     * up to 100 round trips per second against {@code notraius.validity} for one
     * page of work. The port's own javadoc invites the host override; this is it —
     * the ids go to the database as one {@code WHERE id IN (…)} and the map is built
     * from the rows that come back. Missing ids simply stay absent, which is the
     * answer the loop gave too (the caller already treats a missing window as
     * "skip this row").
     */
    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ValidityView> findMany(Collection<UUID> validityIds) {
        if (validityIds == null || validityIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = validityIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ValidityView> windows = new HashMap<>(ids.size());
        for (Validity validity : validityRepository.findAllById(ids)) {
            windows.put(validity.getId(), viewOf(validity));
        }
        return windows;
    }

    private static ValidityView viewOf(Validity validity) {
        return new ValidityView(validity.getId(),
                validity.getStatus() != null ? validity.getStatus().getCode() : null,
                validity.getFromDate(), validity.getToDate());
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
