package com.notraius.modules.core.core.app;

import com.notraius.database.repositories.lease.reference.duration.EntitiesRepository;
import com.notraius.database.repositories.lease.reference.duration.StatusRepository;
import org.primarion.module.communication.errors.web.CommonErrors;
import org.primarion.module.communication.errors.client.NotFoundException;
import org.primarion.module.app.app.app.service.registry.AppStatusResolver;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Host binding of {@link AppStatusResolver} for the notraius database:
 * resolves APPS lifecycle statuses against notraius's own registry
 * (entity {@code APPS}, seeded in the consistency migration).
 */
@Component
public class NotraiusAppStatusResolver implements AppStatusResolver {

    private static final String APPS_ENTITY = "APPS";

    private final EntitiesRepository entitiesRepository;
    private final StatusRepository statusRepository;

    public NotraiusAppStatusResolver(EntitiesRepository entitiesRepository,
                                     StatusRepository statusRepository) {
        this.entitiesRepository = entitiesRepository;
        this.statusRepository = statusRepository;
    }

    @Override
    public UUID resolve(String statusCode) {
        var entity = entitiesRepository.findByCode(APPS_ENTITY)
                .orElseThrow(() -> new NotFoundException(CommonErrors.NOT_FOUND,
                        "Entity not found: " + APPS_ENTITY));
        return statusRepository.findByCodeAndEntityId(statusCode, entity.getId())
                .orElseThrow(() -> new NotFoundException(CommonErrors.NOT_FOUND,
                        "App status not found: " + statusCode))
                .getId();
    }
}
