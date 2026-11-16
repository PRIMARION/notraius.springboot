package com.notraius.modules.engagement;

import com.notraius.database.entities.reference.Status;
import com.notraius.database.repositories.reference.StatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The engagement module writes {@code ACTIVE}/{@code DELETED} for comment,
 * collection and collection-item rows; only the host can turn those into ids.
 * The adapter is the whole translation, so it is tested as data, including the
 * failure a deployment sees when the status rows were never seeded.
 */
class NotariusEngagementStatusesTest {

    private StatusRepository statuses;
    private NotariusEngagementStatuses adapter;

    @BeforeEach
    void setUp() {
        statuses = Mockito.mock(StatusRepository.class);
        adapter = new NotariusEngagementStatuses(statuses);
    }

    @Test
    void resolvesTheStatusRowForTheEntityAndCode() {
        UUID id = UUID.randomUUID();
        when(statuses.findByCodeAndEntityCode("ACTIVE", "COMMENTS"))
                .thenReturn(Optional.of(status(id)));

        assertThat(adapter.find("COMMENTS", "ACTIVE")).contains(id);
        assertThat(adapter.require("COMMENTS", "ACTIVE")).isEqualTo(id);
    }

    @Test
    void codesAreNormalisedAndUnknownRowsAreAbsent() {
        UUID id = UUID.randomUUID();
        when(statuses.findByCodeAndEntityCode("DELETED", "COLLECTION_ITEMS"))
                .thenReturn(Optional.of(status(id)));

        assertThat(adapter.find(" collection_items ", " deleted ")).contains(id);
        assertThat(adapter.find("COMMENTS", "NOPE")).isEmpty();
        assertThat(adapter.find(null, "ACTIVE")).isEmpty();
        assertThat(adapter.find("COMMENTS", null)).isEmpty();
    }

    @Test
    void anUnseededStatusFailsLoudlyRatherThanWritingADanglingId() {
        assertThatThrownBy(() -> adapter.require("COLLECTIONS", "ACTIVE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COLLECTIONS");
    }

    private static Status status(UUID id) {
        Status status = new Status();
        status.setId(id);
        return status;
    }
}
