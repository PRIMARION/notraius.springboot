package com.notraius.modules.lease.engagement;

import com.notraius.database.repositories.asset.asset.PropertyRepository;
import com.notraius.database.repositories.lease.reference.duration.EntitiesRepository;
import org.primarion.module.app.engagement.engagement.core.EngagementReferences;
import org.primarion.module.communication.marketplace.core.guard.MarketplaceBindings;
import org.primarion.module.communication.marketplace.core.marketplace.MarketplaceEntityCodes;
import org.primarion.module.communication.marketplace.core.config.MarketplaceStatuses;
import org.primarion.module.communication.marketplace.card.variant.controller.database.repositories.ProductRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.Locale;

/**
 * Notarius's answer to the engagement module's two reference questions.
 * <p>
 * <b>Where the rows live:</b> the entity code resolves against
 * {@code notraius.entities}; engagement rows keep that id and the database
 * keeps the foreign key. There is no capability file: a code either exists in
 * the host registry or it does not.
 * <p>
 * <b>When a row is engageable:</b> a property while it is PUBLISHED or LEASED —
 * a DRAFT must not accumulate reactions and comments that surface the moment it
 * is published, and a PAUSED, SUSPENDED or DELETED property has left the
 * market. LEASED stays engageable because its page is publicly readable;
 * recommendation eligibility is the feed provider's narrower question. A
 * marketplace product is engageable while it is PUBLISHED. Missing rows deny.
 * <p>
 * This is a row-state fact, asked per request. It is not an action catalogue:
 * there is nothing here saying which entity types "offer" impressions or
 * comments, only whether this row, today, accepts them.
 */
@Component
public class NotariusEngagementReferences implements EngagementReferences {

    private static final String PROPERTY = "PROPERTY";
    private static final String PRODUCT = "PRODUCT";
    private static final String PUBLISHED = "PUBLISHED";
    private static final String LEASED = "LEASED";

    private final EntitiesRepository entities;
    private final PropertyRepository properties;
    private final MarketplaceBindings bindings;
    private final ProductRepository products;

    public NotariusEngagementReferences(EntitiesRepository entities,
                                        PropertyRepository properties,
                                        MarketplaceBindings bindings,
                                        ProductRepository products) {
        this.entities = entities;
        this.properties = properties;
        this.bindings = bindings;
        this.products = products;
    }

    @Override
    public Optional<UUID> findId(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return entities.findByCode(code.trim().toUpperCase(java.util.Locale.ROOT))
                .map(entity -> entity.getId());
    }

    @Override
    public Optional<String> findCode(UUID entityId) {
        return entityId == null
                ? Optional.empty()
                : entities.findById(entityId).map(entity -> entity.getCode());
    }

    @Override
    public boolean exists(String entity, UUID referenceId) {
        if (referenceId == null) {
            return false;
        }
        if (PROPERTY.equalsIgnoreCase(entity)) {
            return properties.existsById(referenceId);
        }
        if (PRODUCT.equalsIgnoreCase(entity)) {
            return products.existsById(referenceId);
        }
        return false;
    }

    @Override
    public boolean visibleTo(UUID userId, String entity, UUID referenceId) {
        if (referenceId == null) {
            return false;
        }
        if (PROPERTY.equalsIgnoreCase(entity)) {
            return properties.findById(referenceId)
                    .map(property -> property.getStatus() != null
                            && (PUBLISHED.equalsIgnoreCase(property.getStatus().getCode())
                                || LEASED.equalsIgnoreCase(property.getStatus().getCode())))
                    .orElse(false);
        }
        if (PRODUCT.equalsIgnoreCase(entity)) {
            return products.findById(referenceId)
                    .map(product -> bindings.isStatus(product.getStatusId(),
                            MarketplaceEntityCodes.PRODUCT, MarketplaceStatuses.PUBLISHED))
                    .orElse(false);
        }
        return false;
    }
}
