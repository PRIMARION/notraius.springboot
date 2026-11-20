package com.notraius.config.communication;

import com.notraius.database.entities.lease.reference.amenity.Entities;
import com.notraius.database.entities.lease.reference.status.Status;
import com.notraius.database.repositories.lease.reference.duration.EntitiesRepository;
import com.notraius.database.repositories.lease.reference.duration.StatusRepository;
import org.primarion.module.utils.utils.security.security.identity.ResolvedUser;
import org.primarion.module.utils.utils.security.security.users.user.UserService;
import org.primarion.module.app.access.consent.database.ActivityRepository;
import org.primarion.module.communication.communication.communication.notification.service.NotificationService;
import org.primarion.module.configs.event.event.EventModule;
import org.primarion.module.configs.event.event.config.EventBindings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.Set;
import java.util.UUID;

/**
 * Binds the notraius microservice's registries to the shared event module.
 * Statuses live under the {@code EVENTS} entity in notraius's registry;
 * activities come from the shared activity registry; notifications flow
 * through the communication module.
 */
@Configuration
@Import(EventModule.class)
public class EventModuleConfig {

    private static final Set<String> REQUIRED_BINDINGS =
            Set.of("user_id", "status_code", "status_view", "activity_code", "notification");

    @Bean
    public EventBindings eventBindings(UserService userService,
                                       StatusRepository statusRepository,
                                       EntitiesRepository entitiesRepository,
                                       ActivityRepository activityRepository,
                                       NotificationService notificationService) {
        EventBindings bindings = EventBindings.builder()
                .bindUser(userService::findById)
                .bindStatusCode(code -> entitiesRepository.findByCode(EventModule.ENTITY_CODE)
                        .flatMap(entity -> statusRepository.findByCodeAndEntityId(code, entity.getId()))
                        .map(Status::getId))
                .bindStatusCodeView(id -> statusRepository.findById(id).map(Status::getCode))
                .bindActivityCode(code -> activityRepository.findByCode(code).map(a -> a.getId()))
                // UUID → code (step validation against the action registry)
                .bindActivityCodeView(id -> activityRepository.findById(id).map(a -> a.getCode()))
                .bindNotification((userId, message) -> {
                    try {
                        notificationService.createNotification(userId, "Event", message, null, null);
                    } catch (RuntimeException e) {
                        // best-effort by contract
                    }
                })
                .build();
        bindings.validate(REQUIRED_BINDINGS);
        return bindings;
    }
}
