package com.notraius.modules.core.app;

import com.notraius.database.repositories.reference.EntitiesRepository;
import com.notraius.database.repositories.reference.StatusRepository;
import org.primarion.module.errors.CommonErrors;
import org.primarion.module.errors.NotFoundException;
import org.primarion.module.infra.webhook.database.repositories.WebhookStatusResolver;
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
