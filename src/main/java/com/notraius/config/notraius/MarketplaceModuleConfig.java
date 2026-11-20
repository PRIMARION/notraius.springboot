package com.notraius.config.notraius;

import com.notraius.database.entities.lease.reference.amenity.Entities;
import com.notraius.database.entities.lease.reference.status.Status;
import com.notraius.database.repositories.lease.LogsRepository;
import com.notraius.database.repositories.lease.reference.duration.EntitiesRepository;
import com.notraius.database.repositories.lease.reference.duration.StatusRepository;
import com.notraius.database.repositories.lease.reference.duration.TypeRepository;
import com.notraius.database.repositories.lease.reference.communication.ValidityRepository;
import org.primarion.module.app.access.consent.database.ActivityRepository;
import org.primarion.module.communication.communication.communication.support.database.repositories.SupportTicketRepository;
import org.primarion.module.communication.marketplace.core.guard.MarketplaceBindings;
import org.primarion.module.communication.marketplace.core.marketplace.MarketplaceEntityCodes;
import org.primarion.module.communication.marketplace.core.config.MarketplaceStatuses;
import org.primarion.module.communication.marketplace.core.card.payment.marketplace.MarketplacePayCatalogue;
import org.primarion.module.utils.utils.security.security.users.user.UserService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.notraius.database.entities.lease.reference.amenity.Validity;

/**
 * Binds the notraius registries to the marketplace module's eleven seams.
 * <p>
 * Dictionary rows (entities, statuses, types, activities, validity windows) are
 * notraius rows converted to the module's view records; users resolve through
 * the shared {@link UserService} because primarion owns identity. Currencies and
 * payment methods are pay rows read through {@link MarketplacePayCatalogue} —
 * notraius mirrors neither catalogue.
 * <p>
 * Attachments arrive as an optional bean from {@code MarketplaceAttachmentsConfig}
 * (which needs {@code AttachmentService} + {@code CommunicationBindings}); when
 * storage is absent the album doors answer MK-2003 and every other path works.
 * <p>
 * Every lambda is written against concrete notraius classes, so a renamed column
 * fails this file's compilation rather than a buyer's checkout.
 */
@Configuration
public class MarketplaceModuleConfig {

    /** The registry entity whose statuses a market-opened validity carries. */
    private static final String VALIDITY_ENTITY = "MARKET_VALIDITY";

    /** Support statuses that mean an adjudication is still open. */
    private static final List<String> OPEN_TICKET_STATUSES = List.of("OPEN", "IN_PROGRESS");

    @Bean
    public MarketplaceBindings marketplaceBindings(
            StatusRepository statusRepository,
            EntitiesRepository entitiesRepository,
            ActivityRepository activityRepository,
            ValidityRepository validityRepository,
            UserService userService,
            MarketplacePayCatalogue payCatalogue,
            SupportTicketRepository ticketRepository,
            LogsRepository logsRepository,
            TypeRepository typeRepository,
            ObjectProvider<MarketplaceBindings.AttachmentSource> attachmentSource) {
        MarketplaceBindings.Builder builder = MarketplaceBindings.builder()
                .statuses(new MarketplaceBindings.StatusSource() {
                    @Override
                    public Optional<UUID> findStatus(String entityCode, String statusCode) {
                        return entitiesRepository.findByCode(entityCode)
                                .flatMap(entity -> statusRepository.findByCodeAndEntityId(statusCode, entity.getId()))
                                .map(Status::getId);
                    }

                    @Override
                    public Optional<String> codeOf(UUID statusId) {
                        return statusRepository.findById(statusId).map(Status::getCode);
                    }
                })
                .entities(code -> entitiesRepository.findByCode(code).map(Entities::getId))
                .types((typeId, entityCode) -> typeRepository.findById(typeId)
                        .filter(type -> type.getEntity() != null
                                && entityCode.equalsIgnoreCase(type.getEntity().getCode()))
                        .filter(type -> type.getStatus() != null
                                && MarketplaceStatuses.ACTIVE.equalsIgnoreCase(type.getStatus().getCode()))
                        .map(type -> type.getId()))
                .activities(code -> activityRepository.findByCode(code).map(activity -> activity.getId()))
                .validities(new MarketplaceBindings.ValiditySource() {
                    @Override
                    public Optional<MarketplaceBindings.Window> find(UUID validityId) {
                        return validityRepository.findById(validityId)
                                .map(validity -> new MarketplaceBindings.Window(
                                        validity.getId(),
                                        validity.getFromDate(),
                                        validity.getToDate(),
                                        validity.getStatus() != null ? validity.getStatus().getCode() : null));
                    }

                    @Override
                    public UUID open(Instant from, Instant to) {
                        Status active = entitiesRepository.findByCode(VALIDITY_ENTITY)
                                .flatMap(entity -> statusRepository.findByCodeAndEntityId(
                                        MarketplaceStatuses.ACTIVE, entity.getId()))
                                .orElseThrow(() -> new IllegalStateException(
                                        "Market validity status not seeded: "
                                                + VALIDITY_ENTITY + '/' + MarketplaceStatuses.ACTIVE));
                        com.notraius.database.entities.lease.reference.amenity.Validity validity =
                                new com.notraius.database.entities.lease.reference.amenity.Validity();
                        validity.setStatus(active);
                        validity.setFromDate(from);
                        validity.setToDate(to);
                        return validityRepository.save(validity).getId();
                    }

                    @Override
                    public List<UUID> lapsed(Instant now, int limit) {
                        return validityRepository
                                .findByToDateBeforeOrderByToDateAsc(now, PageRequest.of(0, limit))
                                .stream()
                                .map(com.notraius.database.entities.lease.reference.amenity.Validity::getId)
                                .toList();
                    }
                })
                .users(userService::findById)
                .currencies(payCatalogue::findCurrency)
                .methods(payCatalogue::findMethod)
                .tickets(new MarketplaceBindings.TicketSource() {
                    @Override
                    public boolean hasOpenTicket(UUID entityId, UUID referenceId) {
                        return ticketRepository.existsByEntityIdAndReferenceIdAndStatusIdIn(
                                entityId, referenceId, openTicketStatusIds(statusRepository));
                    }

                    @Override
                    public boolean isOpenFor(UUID ticketId, UUID entityId, UUID referenceId) {
                        return ticketId != null && ticketRepository
                                .existsByIdAndEntityIdAndReferenceIdAndStatusIdIn(
                                        ticketId, entityId, referenceId,
                                        openTicketStatusIds(statusRepository));
                    }
                })
                .anchors((referenceId, activityCode) -> activityRepository.findByCode(activityCode)
                        .flatMap(activity -> logsRepository.findLatestByReferenceAndActivities(
                                referenceId, List.of(activity.getId())))
                        .map(log -> log.getCreatedAt()));

        // Storage is an organ, not a spine: bind it when the host has it.
        MarketplaceBindings.AttachmentSource attachments = attachmentSource.getIfAvailable();
        if (attachments != null) {
            builder.attachments(attachments);
        }

        MarketplaceBindings bindings = builder.build();
        bindings.validateAll();
        return bindings;
    }

    private static List<UUID> openTicketStatusIds(StatusRepository statusRepository) {
        return OPEN_TICKET_STATUSES.stream()
                .map(code -> statusRepository.findByCodeAndEntityCode(code, "SUPPORT_TICKET"))
                .flatMap(Optional::stream)
                .map(Status::getId)
                .toList();
    }

}
