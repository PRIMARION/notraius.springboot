package com.notraius.modules.engagement;

import com.notraius.database.entities.asset.Property;
import com.notraius.database.entities.reference.Status;
import com.notraius.database.repositories.asset.PropertyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.primarion.module.engagement.feed.FeedCandidate;
import org.primarion.module.engagement.feed.FeedCard;
import org.primarion.module.engagement.summary.EngagementSummaryService;
import org.primarion.module.marketplace.core.MarketplaceBindings;
import org.primarion.module.marketplace.core.MarketplaceEntityCodes;
import org.primarion.module.marketplace.core.MarketplaceStatuses;
import org.primarion.module.marketplace.variant.database.entities.Product;
import org.primarion.module.marketplace.variant.database.repositories.ProductRepository;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The lifecycle ↔ visibility pin (UPGRADE.md §15 E6 / §16 M2): the pool asks
 * the repository for PUBLISHED only, and the hydrator re-checks PUBLISHED at
 * chunk time — so unpublish, suspend or delete drops a listing out of the
 * feed without anyone touching the cached generation, while LEASED stays
 * engageable row-level yet never recommended. A republished listing re-enters
 * with its earned affinity intact, because nothing was ever deleted.
 */
class NotariusFeedSeamsTest {

    private static final UUID ROW_PUBLISHED = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROW_LEASED = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID ROW_PAUSED = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private PropertyRepository properties;
    private ProductRepository products;
    private MarketplaceBindings bindings;
    private NotariusFeedCandidateProvider provider;
    private NotariusFeedHydrator hydrator;

    @BeforeEach
    void setUp() {
        properties = Mockito.mock(PropertyRepository.class);
        products = Mockito.mock(ProductRepository.class);
        bindings = Mockito.mock(MarketplaceBindings.class);
        Mockito.when(bindings.isStatus(any(), any(), any())).thenAnswer(inv ->
                inv.getArgument(2) != null && inv.getArgument(2).equals("PUBLISHED"));
        provider = new NotariusFeedCandidateProvider(properties);
        EngagementSummaryService summary = Mockito.mock(EngagementSummaryService.class);
        Mockito.when(summary.summarize(any(), anyList())).thenReturn(Map.of());
        hydrator = new NotariusFeedHydrator(properties, products, bindings, summary);
    }

    private static Property property(UUID id, String statusCode) {
        Property property = new Property();
        property.setId(id);
        property.setTitle("A home");
        Status status = new Status();
        status.setCode(statusCode);
        property.setStatus(status);
        return property;
    }

    @Test
    void thePoolAsksForPublishedOnly() {
        when(properties.search(eq(null), eq("PUBLISHED"), eq(null), eq(null), eq(null), any()))
                .thenReturn(new PageImpl<>(List.of(property(ROW_PUBLISHED, "PUBLISHED")),
                        PageRequest.of(0, 10), 1));

        List<FeedCandidate> candidates = provider.candidates(10);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).referenceId()).isEqualTo(ROW_PUBLISHED);
        verify(properties).search(eq(null), eq("PUBLISHED"), eq(null), eq(null), eq(null), any());
    }

    @Test
    void theHydratorServesPublishedOnly() {
        // A LEASED row is engageable row-level but never recommended; a PAUSED
        // row has left the market entirely. Both drop out at chunk time.
        when(properties.findAllById(anyList())).thenReturn(List.of(
                property(ROW_PUBLISHED, "PUBLISHED"),
                property(ROW_LEASED, "LEASED"),
                property(ROW_PAUSED, "PAUSED")));

        List<FeedCandidate> candidates = List.of(
                new FeedCandidate("PROPERTY", ROW_PUBLISHED, Instant.now()),
                new FeedCandidate("PROPERTY", ROW_LEASED, Instant.now()),
                new FeedCandidate("PROPERTY", ROW_PAUSED, Instant.now()));

        List<FeedCard> cards = hydrator.hydrate(candidates);

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).referenceId()).isEqualTo(ROW_PUBLISHED);
    }

    @Test
    void aCardCarriesTheSameFactsTheMarketPageReads() {
        when(properties.findAllById(anyList()))
                .thenReturn(List.of(property(ROW_PUBLISHED, "PUBLISHED")));

        EngagementSummaryService summary = Mockito.mock(EngagementSummaryService.class);
        when(summary.summarize(eq("PROPERTY"), anyList())).thenReturn(Map.of(
                ROW_PUBLISHED, new EngagementSummaryService.Summary(12, 3, 5)));
        NotariusFeedHydrator counting = new NotariusFeedHydrator(properties, products, bindings, summary);

        FeedCard card = counting.hydrate(List.of(
                new FeedCandidate("PROPERTY", ROW_PUBLISHED, Instant.now()))).get(0);

        assertThat(card.meta()).containsEntry("views", 12L)
                .containsEntry("reactions", 3L)
                .containsEntry("comments", 5L);
    }

    // ── The marketplace surface (§20) ───────────────────────────────────

    @Test
    void theMarketPoolAsksForPublishedGoodsOfActiveStores() {
        Product product = product(ROW_PUBLISHED, "PUBLISHED");
        when(products.feedPool(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(product), PageRequest.of(0, 10), 1));

        NotariusMarketFeedCandidateProvider marketProvider =
                new NotariusMarketFeedCandidateProvider(products, bindings);

        List<FeedCandidate> candidates = marketProvider.candidates(10);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).entity()).isEqualTo("PRODUCT");
        assertThat(candidates.get(0).referenceId()).isEqualTo(ROW_PUBLISHED);
        verify(products).feedPool(
                bindings.statusId(MarketplaceEntityCodes.PRODUCT, MarketplaceStatuses.PUBLISHED),
                bindings.statusId(MarketplaceEntityCodes.STORE, MarketplaceStatuses.ACTIVE),
                PageRequest.of(0, 10));
    }

    @Test
    void theHydratorServesPublishedProductsOnly() {
        UUID archived = UUID.fromString("88888888-8888-8888-8888-888888888888");
        when(products.findAllById(anyList())).thenReturn(List.of(
                product(ROW_PUBLISHED, "PUBLISHED"),
                product(archived, "ARCHIVED")));

        FeedCard card = hydrator.hydrate(List.of(
                new FeedCandidate("PRODUCT", ROW_PUBLISHED, Instant.now()),
                new FeedCandidate("PRODUCT", archived, Instant.now()))).get(0);

        assertThat(card.entity()).isEqualTo("PRODUCT");
        assertThat(card.referenceId()).isEqualTo(ROW_PUBLISHED);
        assertThat(card.meta()).containsEntry("storeId", ROW_STORE);
    }

    private static final UUID ROW_STORE = UUID.fromString("99999999-9999-9999-9999-999999999999");

    private static Product product(UUID id, String statusCodeWord) {
        Product product = new Product();
        product.setId(id);
        product.setLabel("A good");
        product.setDescription("Something worth buying");
        product.setStoreId(ROW_STORE);
        // The bindings stub answers isStatus by word equality, so any stable
        // id per word serves; the word is what decides.
        product.setStatusId(java.util.UUID.nameUUIDFromBytes(statusCodeWord.getBytes()));
        return product;
    }
}
