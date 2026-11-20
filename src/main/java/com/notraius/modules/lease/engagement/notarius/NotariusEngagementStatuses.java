package com.notraius.modules.lease.engagement.notarius;

import com.notraius.database.repositories.lease.reference.duration.StatusRepository;
import org.primarion.module.app.engagement.engagement.core.EngagementStatuses;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Notarius's status catalogue, narrowed to the codes the engagement module
 * writes. Comments, collections and collection items carry a {@code status_id}
 * like every other shared table, so the ids come from {@code notraius.statuses}
 * under the entity codes this module names.
 */
@Component
public class NotariusEngagementStatuses implements EngagementStatuses {

    private final StatusRepository statuses;

    public NotariusEngagementStatuses(StatusRepository statuses) {
        this.statuses = statuses;
    }

    @Override
    public Optional<UUID> find(String entityCode, String statusCode) {
        if (entityCode == null || statusCode == null) {
            return Optional.empty();
        }
        return statuses.findByCodeAndEntityCode(statusCode.trim().toUpperCase(Locale.ROOT),
                        entityCode.trim().toUpperCase(Locale.ROOT))
                .map(status -> status.getId());
    }
}
