package com.notraius.modules.lease.engagement.notarius;

import org.primarion.module.app.engagement.impression.feed.feed.FeedCandidate;
import org.primarion.module.app.engagement.impression.feed.service.FeedCandidateProvider;
import org.primarion.module.communication.marketplace.core.guard.MarketplaceBindings;
import org.primarion.module.communication.marketplace.core.marketplace.MarketplaceEntityCodes;
import org.primarion.module.communication.marketplace.core.config.MarketplaceStatuses;
import org.primarion.module.communication.marketplace.card.variant.controller.database.repositories.ProductRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The marketplace half of notarius' feed pool: published goods of active
 * stores, newest first (§20).
 * <p>
 * Publication is the eligibility rule — a DRAFT or ARCHIVED product never
 * enters the pool — and the {@code feedPool} query's store join keeps a paused
 * store's shelf out of the recommendations even though its product rows still
 * say PUBLISHED. The cap comes from {@code app.engagement.candidate-pool},
 * exactly as the property pool reads it; the ranking itself is the feed
 * module's, unchanged — this seam only says which rows are eligible at all.
 */
@Component
public class NotariusMarketFeedCandidateProvider implements FeedCandidateProvider {

    private static final String PRODUCT = "PRODUCT";
    private static final int MAX_LIMIT = 1000;

    private final ProductRepository products;
    private final MarketplaceBindings bindings;

    public NotariusMarketFeedCandidateProvider(ProductRepository products,
                                               MarketplaceBindings bindings) {
        this.products = products;
        this.bindings = bindings;
    }

    @Override
    public List<FeedCandidate> candidates(int limit) {
        int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return products.feedPool(
                        bindings.statusId(MarketplaceEntityCodes.PRODUCT, MarketplaceStatuses.PUBLISHED),
                        bindings.statusId(MarketplaceEntityCodes.STORE, MarketplaceStatuses.ACTIVE),
                        PageRequest.of(0, size))
                .stream()
                .map(product -> new FeedCandidate(PRODUCT, product.getId(), product.getCreatedAt()))
                .toList();
    }
}
