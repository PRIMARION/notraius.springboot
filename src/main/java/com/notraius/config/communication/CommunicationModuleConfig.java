package com.notraius.config.communication;

import com.fasterxml.jackson.databind.JsonNode;
import com.notraius.common.services.LogsService;
import com.notraius.database.entities.Log;
import org.primarion.module.access.activity.database.entities.Activity;
import com.notraius.database.entities.reference.Entities;
import com.notraius.database.entities.reference.Status;
import org.primarion.module.access.activity.database.repositories.ActivityRepository;
import com.notraius.database.repositories.reference.EntitiesRepository;
import com.notraius.database.repositories.reference.StatusRepository;
import org.primarion.module.utils.security.identity.ResolvedUser;
import com.notraius.database.repositories.reference.CommunicationRoleRepository;
import org.primarion.module.utils.security.users.UserService;
import org.primarion.module.communication.config.CommunicationBindings;
import org.primarion.module.communication.core.dto.ResolvedRegistryEntry;
import org.primarion.module.communication.core.dto.ResolvedRole;
import org.primarion.module.communication.core.dto.ResolvedStatus;
import org.primarion.module.communication.core.EntityByIdResolver;
import org.primarion.module.communication.core.AbstractLogsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Wires the notraius microservice's types into the communication module
 * (#W1-07 v2 wiring protocol). Users resolve through the shared
 * {@link UserService} (primarion is the identity provider), while
 * statuses/entities/activities/logs are local rows converted to module-owned
 * view records. Every converter lambda is written against concrete logistic
 * classes, so a missing column or accessor fails THIS file's compilation.
 */
@Configuration
public class CommunicationModuleConfig {

    private static final Set<String> REQUIRED_FK_COLUMNS = Set.of(
            "user_id", "status_id", "entity_id", "role_id", "activity_id", "log_id");

    @Bean
    public CommunicationBindings communicationBindings(
            UserService userService,
            StatusRepository statusRepository,
            EntitiesRepository entitiesRepository,
            CommunicationRoleRepository communicationRoleRepository,
            ActivityRepository activityRepository,
            LogsService logsService) {
        CommunicationBindings bindings = CommunicationBindings.builder()
                .bind("user_id", ResolvedUser.class, userService::findById)
                .bind("status_id", ResolvedStatus.class,
                        id -> statusRepository.findById(id)
                                .map(status -> new ResolvedStatus(status.getId(), status.getCode(), status.getDescription())))
                .bindByCode("status_id", ResolvedStatus.class,
                        code -> statusRepository.findByCode(code)
                                .map(status -> new ResolvedStatus(status.getId(), status.getCode(), status.getDescription())))
                .bindByCodeAndEntityId("status_id", ResolvedStatus.class,
                        (code, entityId) -> statusRepository.findByCodeAndEntityId(code, entityId)
                                .map(status -> new ResolvedStatus(status.getId(), status.getCode(), status.getDescription())))
                .bind("entity_id", ResolvedRegistryEntry.class,
                        id -> entitiesRepository.findById(id)
                                .map(entity -> new ResolvedRegistryEntry(entity.getId(), entity.getCode())))
                .bindByCode("entity_id", ResolvedRegistryEntry.class,
                        code -> entitiesRepository.findByCode(code)
                                .map(entity -> new ResolvedRegistryEntry(entity.getId(), entity.getCode())))
                .bind("role_id", ResolvedRole.class,
                        id -> communicationRoleRepository.findById(id)
                                .map(role -> new ResolvedRole(role.getId(), role.getCode(), role.getLabel())))
                .bindByCode("role_id", ResolvedRole.class,
                        code -> communicationRoleRepository.findByCode(code)
                                .map(role -> new ResolvedRole(role.getId(), role.getCode(), role.getLabel())))
                .bindByCodeAndEntityId("role_id", ResolvedRole.class,
                        (code, entityId) -> communicationRoleRepository.findByCodeAndEntityId(code, entityId)
                                .map(role -> new ResolvedRole(role.getId(), role.getCode(), role.getLabel())))
                .bind("activity_id", ResolvedRegistryEntry.class,
                        id -> activityRepository.findById(id)
                                .map(activity -> new ResolvedRegistryEntry(activity.getId(), activity.getCode())))
                .bindByCode("activity_id", ResolvedRegistryEntry.class,
                        code -> activityRepository.findByCode(code)
                                .map(activity -> new ResolvedRegistryEntry(activity.getId(), activity.getCode())))
                .bind("log_id", ResolvedRegistryEntry.class,
                        id -> logsService.getById(id)
                                .map(log -> new ResolvedRegistryEntry(log.getId(), null)))
                .build();
        bindings.validate(REQUIRED_FK_COLUMNS);
        return bindings;
    }

    /**
     * Lets the shared {@code AttachmentService} hydrate the {@code entity}
     * transient reference on attachment reads (the attachment row stores only
     * the owning entity UUID; the code lives in the notraius entities
     * registry).
     */
    @Bean
    public EntityByIdResolver entityByIdResolver(EntitiesRepository entitiesRepository) {
        return id -> entitiesRepository.findById(id)
                .map(entity -> new ResolvedRegistryEntry(entity.getId(), entity.getCode()));
    }

    /**
     * Bridges the communication module's {@link AbstractLogsService} contract
     * (module-owned {@link ResolvedRegistryEntry} views) onto the concrete
     * {@link LogsService}, resolving the referenced rows by id.
     */
    @Bean
    public AbstractLogsService communicationLogsService(LogsService logsService,
                                                        EntitiesRepository entitiesRepository,
                                                        ActivityRepository activityRepository) {
        return new AbstractLogsService() {
            @Override
            public ResolvedRegistryEntry create(ResolvedRegistryEntry entity,
                                                ResolvedRegistryEntry activity,
                                                JsonNode oldData, JsonNode newData) {
                return create(entity, activity, oldData, newData, null);
            }

            @Override
            public ResolvedRegistryEntry create(ResolvedRegistryEntry entity,
                                                ResolvedRegistryEntry activity,
                                                JsonNode oldData, JsonNode newData, UUID referenceId) {
                Entities concreteEntity = entitiesRepository.getReferenceById(entity.getId());
                Activity concreteActivity = activity != null
                        ? activityRepository.getReferenceById(activity.getId())
                        : null;
                Log created = logsService.create(concreteEntity, concreteActivity,
                        oldData, newData, referenceId);
                return new ResolvedRegistryEntry(created.getId(), null);
            }

            @Override
            public Optional<ResolvedRegistryEntry> getById(UUID logId) {
                return logsService.getById(logId)
                        .map(log -> new ResolvedRegistryEntry(log.getId(), null));
            }
        };
    }
}
