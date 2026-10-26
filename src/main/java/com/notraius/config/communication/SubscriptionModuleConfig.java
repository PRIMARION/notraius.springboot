package com.notraius.config.communication;

import com.notraius.database.entities.reference.Status;
import com.notraius.database.repositories.reference.EntitiesRepository;
import com.notraius.database.repositories.reference.StatusRepository;
import org.primarion.module.errors.CommonErrors;
import org.primarion.module.errors.NotFoundException;
import org.primarion.database.entities.lifecycle.StatusPort;
import org.primarion.module.utils.security.users.UserService;
import org.primarion.module.communication.core.dto.ResolvedStatus;
import org.primarion.module.access.consent.service.subscription.UsageCounter;
import org.primarion.module.subscription.config.SubscriptionBindings;
import com.notraius.database.repositories.LogsRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Binds the notraius microservice's user/status resolvers to the shared
 * subscription module's contracts. Wallet debits are not bound here — the
 * subscription module calls the pay microservice's billing endpoints over
 * HTTP (see {@code SubscriptionBillingController} in pay), authenticated with
 * the platform private key.
 */
@Configuration
public class SubscriptionModuleConfig {

    /** Registry entity that carries the subscription lifecycle statuses. */
    private static final String SUBSCRIPTION_ENTITY = "SUBSCRIPTIONS";

    private static final Set<String> REQUIRED_FK_COLUMNS = Set.of("user_id", "status_id", "status_code");

    @Bean
    public SubscriptionBindings subscriptionBindings(
            UserService userService,
            StatusRepository statusRepository,
            EntitiesRepository entitiesRepository) {
        SubscriptionBindings bindings = SubscriptionBindings.builder()
                .bindUser(userService::findById)
                .bindStatus(id -> statusRepository.findById(id)
                        .map(status -> new ResolvedStatus(status.getId(), status.getCode(), status.getDescription())))
                // #CONS-01: reverse lookup for rows the module creates —
                // statuses live under the SUBSCRIPTION entity in the registry.
                .bindStatusCode(code -> entitiesRepository.findByCode(SUBSCRIPTION_ENTITY)
                        .flatMap(entity -> statusRepository.findByCodeAndEntityId(code, entity.getId()))
                        .map(Status::getId))
                .build();
        bindings.validate(REQUIRED_FK_COLUMNS);
        return bindings;
    }

    @Bean
    public StatusPort statusPort(StatusRepository statusRepository,
                                 EntitiesRepository entitiesRepository) {
        return new StatusPort() {
            @Override
            public UUID resolve(String entityCode, String statusCode) {
                UUID entityId = entitiesRepository.findByCode(entityCode)
                        .orElseThrow(() -> new NotFoundException(CommonErrors.NOT_FOUND,
                                "Entity not found: " + entityCode))
                        .getId();
                return statusRepository.findByCodeAndEntityId(statusCode, entityId)
                        .orElseThrow(() -> new NotFoundException(CommonErrors.NOT_FOUND,
                                "Status not found: " + statusCode))
                        .getId();
            }

            @Override
            public Optional<String> code(UUID statusId) {
                return statusRepository.findById(statusId).map(Status::getCode);
            }
        };
    }

    @Bean
    public UsageCounter usageCounter(LogsRepository logsRepository) {
        // Window = the subscription's own validity columns; to == null means
        // open-ended (FREE) — count up to now. All SQL lives in the
        // repository — this config never touches SQL.
        return (userId, activityId, operation, from, to) ->
                logsRepository.countSubscriptionUsage(userId, activityId, operation, from, to);
    }
}