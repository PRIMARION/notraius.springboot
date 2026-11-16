package com.notraius.modules.engagement;

import com.notraius.database.entities.asset.Property;
import com.notraius.database.entities.reference.Entities;
import com.notraius.database.entities.reference.Status;
import com.notraius.database.repositories.asset.PropertyRepository;
import com.notraius.database.repositories.reference.EntitiesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.primarion.module.marketplace.core.MarketplaceBindings;
import org.primarion.module.marketplace.variant.database.entities.Product;
import org.primarion.module.marketplace.variant.database.repositories.ProductRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The notarius reference adapter: entity codes resolve against the host
 * registry, existence is the row's own table, and engageability is the row's
 * status — PUBLISHED or LEASED for a property, PUBLISHED for a product. The
 * engagement module asks these questions per request; there is no capability
 * file and no action enum in the answer.
 */
class NotariusEngagementReferencesTest {

    private static final UUID ROW = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ENTITY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private EntitiesRepository entities;
    private PropertyRepository properties;
    private ProductRepository products;
    private MarketplaceBindings bindings;
    private NotariusEngagementReferences references;

    @BeforeEach
    void setUp() {
        entities = Mockito.mock(EntitiesRepository.class);
        properties = Mockito.mock(PropertyRepository.class);
        products = Mockito.mock(ProductRepository.class);
        bindings = Mockito.mock(MarketplaceBindings.class);
        when(bindings.isStatus(any(), any(), any())).thenAnswer(inv ->
                inv.getArgument(2) != null && inv.getArgument(2).equals("PUBLISHED"));
        references = new NotariusEngagementReferences(entities, properties, bindings, products);
    }

    @Test
    void entityCodesResolveAgainstTheRegistry() {
        Entities entity = new Entities();
        entity.setId(ENTITY_ID);
        entity.setCode("PROPERTY");
        when(entities.findByCode("PROPERTY")).thenReturn(Optional.of(entity));
        when(entities.findById(ENTITY_ID)).thenReturn(Optional.of(entity));

        assertThat(references.findId("property")).contains(ENTITY_ID);
        assertThat(references.findCode(ENTITY_ID)).contains("PROPERTY");
        assertThat(references.findId("VISIT")).isEmpty();
        assertThat(references.findId(null)).isEmpty();
    }

    @Test
    void existenceIsTheRowsOwnTable() {
        when(properties.existsById(ROW)).thenReturn(true);
        when(products.existsById(ROW)).thenReturn(true);

        assertThat(references.exists("PROPERTY", ROW)).isTrue();
        assertThat(references.exists("PRODUCT", ROW)).isTrue();
        assertThat(references.exists("VARIANT", ROW)).isFalse();
        assertThat(references.exists("PROPERTY", null)).isFalse();
    }

    @Test
    void publishedAndLeasedPropertiesAreEngageable() {
        for (String status : new String[]{"PUBLISHED", "LEASED"}) {
            when(properties.findById(ROW)).thenReturn(Optional.of(property(status)));

            assertThat(references.visibleTo(USER, "PROPERTY", ROW)).as(status).isTrue();
            assertThat(references.visibleTo(null, "PROPERTY", ROW))
                    .as("anonymous visitor on " + status).isTrue();
        }
    }

    @Test
    void everyOtherStatusIsDenied() {
        for (String status : new String[]{"DRAFT", "PAUSED", "SUSPENDED", "DELETED"}) {
            when(properties.findById(ROW)).thenReturn(Optional.of(property(status)));

            assertThat(references.visibleTo(USER, "PROPERTY", ROW)).as(status).isFalse();
        }
    }

    @Test
    void aMissingPropertyDenies() {
        when(properties.findById(ROW)).thenReturn(Optional.empty());

        assertThat(references.visibleTo(USER, "PROPERTY", ROW)).isFalse();
    }

    @Test
    void aPublishedProductIsEngageableAndOtherStatesAreNot() {
        when(products.findById(ROW)).thenReturn(Optional.of(product()));

        assertThat(references.visibleTo(USER, "PRODUCT", ROW)).isTrue();

        Mockito.when(bindings.isStatus(any(), any(), any())).thenReturn(false);
        assertThat(references.visibleTo(USER, "PRODUCT", ROW)).isFalse();
    }

    @Test
    void aMissingProductDenies() {
        when(products.findById(ROW)).thenReturn(Optional.empty());

        assertThat(references.visibleTo(USER, "PRODUCT", ROW)).isFalse();
    }

    @Test
    void unknownCodesAndNullReferencesDeny() {
        assertThat(references.visibleTo(USER, "VISIT", ROW)).isFalse();
        assertThat(references.visibleTo(USER, null, ROW)).isFalse();
        assertThat(references.visibleTo(USER, "PROPERTY", null)).isFalse();
    }

    private static Product product() {
        Product product = new Product();
        product.setId(ROW);
        product.setStatusId(UUID.randomUUID());
        return product;
    }

    private static Property property(String statusCode) {
        Property property = new Property();
        Status status = new Status();
        status.setCode(statusCode);
        property.setStatus(status);
        return property;
    }
}
