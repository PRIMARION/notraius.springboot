package com.notraius.modules.engagement;

import com.notraius.database.entities.asset.Property;
import com.notraius.database.repositories.asset.PropertyRepository;
import org.primarion.module.engagement.feed.FeedCandidate;
import org.primarion.module.engagement.feed.FeedCard;
import org.primarion.module.engagement.feed.FeedHydrator;
import org.primarion.module.engagement.summary.EngagementSummaryService;
import org.primarion.module.marketplace.core.MarketplaceBindings;
import org.primarion.module.marketplace.core.MarketplaceEntityCodes;
import org.primarion.module.marketplace.core.MarketplaceStatuses;
import org.primarion.module.marketplace.variant.database.entities.Product;
import org.primarion.module.marketplace.variant.database.repositories.ProductRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns ranked references into cards at chunk time — properties and marketplace
 * goods (§20), the host's two engageable surfaces.
 * <p>
 * Re-checks eligibility here as well: a row can leave the market between the
 * refresh and a later chunk, and it must vanish then rather than be served
 * from a frozen list. (A LEASED property also drops out — the pool is
 * PUBLISHED-only by decision; it stays engageable row-level. Products follow
 * the same rule: PUBLISHED or nothing.) Batched by construction — one chunk,
 * two queries at most, one summary read per surface.
 * <p>
 * Each card also carries the loop's engagement facts, so the feed card and the
 * detail page render from the same shape: one card component for both
 * discovery surfaces.
 */
@Component
public class NotariusFeedHydrator implements FeedHydrator {

    private static final String PROPERTY = "PROPERTY";
    private static final String PRODUCT = "PRODUCT";
    private static final String PUBLISHED = "PUBLISHED";
    private static final int SUBTITLE_LENGTH = 180;

    private final PropertyRepository properties;
    private final ProductRepository products;
    private final MarketplaceBindings bindings;
    private final EngagementSummaryService engagement;

    public NotariusFeedHydrator(PropertyRepository properties,
                                ProductRepository products,
                                MarketplaceBindings bindings,
                                EngagementSummaryService engagement) {
        this.properties = properties;
        this.products = products;
        this.bindings = bindings;
        this.engagement = engagement;
    }

    @Override
    public List<FeedCard> hydrate(List<FeedCandidate> candidates) {
        List<UUID> propertyIds = candidates.stream()
                .filter(c -> PROPERTY.equalsIgnoreCase(c.entity()))
                .map(FeedCandidate::referenceId)
                .toList();
        List<UUID> productIds = candidates.stream()
                .filter(c -> PRODUCT.equalsIgnoreCase(c.entity()))
                .map(FeedCandidate::referenceId)
                .toList();

        Map<UUID, Property> propertyById = propertyIds.isEmpty() ? Map.of()
                : properties.findAllById(propertyIds).stream()
                .filter(p -> p.getStatus() != null
                        && PUBLISHED.equalsIgnoreCase(p.getStatus().getCode()))
                .collect(Collectors.toMap(Property::getId, Function.identity()));
        Map<UUID, org.primarion.module.marketplace.variant.database.entities.Product> productById =
                productIds.isEmpty() ? Map.of()
                : products.findAllById(productIds).stream()
                .filter(p -> bindings.isStatus(p.getStatusId(),
                        MarketplaceEntityCodes.PRODUCT, MarketplaceStatuses.PUBLISHED))
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        // One batched read of the loop's facts for the whole chunk, per surface.
        Map<UUID, EngagementSummaryService.Summary> propertyFacts = propertyById.isEmpty()
                ? Map.of() : engagement.summarize(PROPERTY, List.copyOf(propertyById.keySet()));
        Map<UUID, EngagementSummaryService.Summary> productFacts = productById.isEmpty()
                ? Map.of() : engagement.summarize(PRODUCT, List.copyOf(productById.keySet()));

        return candidates.stream()
                .map(candidate -> {
                    if (PROPERTY.equalsIgnoreCase(candidate.entity())) {
                        Property property = propertyById.get(candidate.referenceId());
                        return property == null ? null : card(property, propertyFacts);
                    }
                    if (PRODUCT.equalsIgnoreCase(candidate.entity())) {
                        Product product = productById.get(candidate.referenceId());
                        return product == null ? null : card(product, productFacts);
                    }
                    return null;
                })
                .filter(card -> card != null)
                .toList();
    }

    private static FeedCard card(Property property, Map<UUID, EngagementSummaryService.Summary> facts) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("ownerId", property.getOwnerId());
        putIfPresent(meta, "typeId", property.getType() == null ? null : property.getType().getId());
        putIfPresent(meta, "currencyId", property.getCurrencyId());
        putIfPresent(meta, "stateId", property.getStateId());
        putIfPresent(meta, "locationId", property.getLocationId());
        meta.put("createdAt", property.getCreatedAt());
        putFacts(meta, facts.get(property.getId()));

        return new FeedCard(PROPERTY, property.getId(), property.getTitle(),
                subtitle(property.getDescription()), null, meta);
    }

    private static FeedCard card(Product product, Map<UUID, EngagementSummaryService.Summary> facts) {
        Map<String, Object> meta = new LinkedHashMap<>();
        putIfPresent(meta, "storeId", product.getStoreId());
        putIfPresent(meta, "categoryId", product.getCategoryId());
        putIfPresent(meta, "type", product.getType());
        meta.put("createdAt", product.getCreatedAt());
        putFacts(meta, facts.get(product.getId()));

        return new FeedCard(PRODUCT, product.getId(), product.getLabel(),
                subtitle(product.getDescription()), null, meta);
    }

    /** The engagement facts, under the same keys every surface uses. */
    private static void putFacts(Map<String, Object> meta, EngagementSummaryService.Summary summary) {
        if (summary != null) {
            meta.put("views", summary.views());
            meta.put("reactions", summary.reactions());
            meta.put("comments", summary.comments());
        }
    }

    private static void putIfPresent(Map<String, Object> meta, String key, Object value) {
        if (value != null) {
            meta.put(key, value);
        }
    }

    private static String subtitle(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String compact = description.trim().replaceAll("\\s+", " ");
        return compact.length() <= SUBTITLE_LENGTH
                ? compact
                : compact.substring(0, SUBTITLE_LENGTH - 1) + "…";
    }
}
