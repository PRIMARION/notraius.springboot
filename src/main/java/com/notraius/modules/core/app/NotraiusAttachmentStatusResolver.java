package com.notraius.modules.core.app;

import com.notraius.database.repositories.reference.EntitiesRepository;
import com.notraius.database.repositories.reference.StatusRepository;
import org.primarion.module.errors.CommonErrors;
import org.primarion.module.errors.NotFoundException;
import org.primarion.module.infra.storage.database.repositories.attachment.AttachmentStatusResolver;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Host binding of {@link AttachmentStatusResolver} for the notraius
 * database: resolves ATTACHMENTS lifecycle statuses against notraius's own
 * registry (entity {@code ATTACHMENTS}, seeded in V8).
 */
@Component
public class NotraiusAttachmentStatusResolver implements AttachmentStatusResolver {

    private static final String ATTACHMENTS_ENTITY = "ATTACHMENTS";

    private final EntitiesRepository entitiesRepository;
    private final StatusRepository statusRepository;

    public NotraiusAttachmentStatusResolver(EntitiesRepository entitiesRepository,
                                            StatusRepository statusRepository) {
        this.entitiesRepository = entitiesRepository;
        this.statusRepository = statusRepository;
    }

    @Override
    public UUID resolve(String statusCode) {
        var entity = entitiesRepository.findByCode(ATTACHMENTS_ENTITY)
                .orElseThrow(() -> new NotFoundException(CommonErrors.NOT_FOUND,
                        "Entity not found: " + ATTACHMENTS_ENTITY));
        return statusRepository.findByCodeAndEntityId(statusCode, entity.getId())
                .orElseThrow(() -> new NotFoundException(CommonErrors.NOT_FOUND,
                        "Attachment status not found: " + statusCode))
                .getId();
    }
}
