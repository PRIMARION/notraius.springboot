package com.notraius.modules.core.app;

import com.notraius.database.repositories.reference.EntitiesRepository;
import com.notraius.database.repositories.reference.StatusRepository;
import org.primarion.module.errors.CommonErrors;
import org.primarion.module.errors.NotFoundException;
import org.primarion.module.app.service.registry.AppStatusResolver;
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
