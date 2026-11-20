package com.notraius.modules.core.core.app;

import com.notraius.database.repositories.lease.reference.duration.EntitiesRepository;
import com.notraius.database.repositories.lease.reference.duration.StatusRepository;
import org.primarion.module.communication.errors.web.CommonErrors;
import org.primarion.module.communication.errors.client.NotFoundException;
import org.primarion.module.communication.infra.anomaly.webhook.core.database.repositories.WebhookStatusResolver;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Host binding of {@link WebhookStatusResolver} for the notraius database:
 * resolves WEBHOOKS lifecycle statuses against notraius's own registry
 * (entity {code WEBHOOKS}, seeded in V8).
 */
@Component
public class NotraiusWebhookStatusResolver implements WebhookStatusResolver {

    private static final String WEBHOOKS_ENTITY = "WEBHOOKS";

    private final EntitiesRepository entitiesRepository;
    private final StatusRepository statusRepository;

    public NotraiusWebhookStatusResolver(EntitiesRepository entitiesRepository,
                                        StatusRepository statusRepository) {
        this.entitiesRepository = entitiesRepository;
        this.statusRepository = statusRepository;
    }

    @Override
    public UUID resolve(String statusCode) {
        var entity = entitiesRepository.findByCode(WEBHOOKS_ENTITY)
                .orElseThrow(() -> new NotFoundException(CommonErrors.NOT_FOUND,
                        "Entity not found: " + WEBHOOKS_ENTITY));
        return statusRepository.findByCodeAndEntityId(statusCode, entity.getId())
                .orElseThrow(() -> new NotFoundException(CommonErrors.NOT_FOUND,
                        "Webhook status not found: " + statusCode))
                .getId();
    }
}
