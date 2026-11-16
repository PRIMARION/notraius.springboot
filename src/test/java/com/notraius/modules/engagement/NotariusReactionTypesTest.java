package com.notraius.modules.engagement;

import com.notraius.database.entities.reference.Entities;
import com.notraius.database.entities.reference.Status;
import com.notraius.database.entities.reference.Type;
import com.notraius.database.repositories.reference.TypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.primarion.module.engagement.impression.ReactionTypes.HostType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Reaction kinds are the REACTIONS type catalogue, not a deployment list:
 * a live kind is an ACTIVE type row; an absent or retired code is refused for
 * new reactions, while a stored reaction keeps resolving through its type id.
 */
class NotariusReactionTypesTest {

    private static final UUID TYPE_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private TypeRepository types;
    private NotariusReactionTypes kinds;

    @BeforeEach
    void setUp() {
        types = Mockito.mock(TypeRepository.class);
        kinds = new NotariusReactionTypes(types);
    }

    @Test
    void anActiveTypeUnderReactionsIsALiveKind() {
        when(types.findByEntityCodeAndCode("REACTIONS", "LOVE"))
                .thenReturn(Optional.of(type("LOVE", "Love", "REACTIONS", "ACTIVE")));

        assertThat(kinds.findLive("love")).map(HostType::code).hasValue("LOVE");
        assertThat(kinds.findLive(" LOVE ")).map(HostType::code).hasValue("LOVE");
    }

    @Test
    void aRetiredTypeIsNotOfferedButStillResolvesById() {
        Type retired = type("LIKE", "Like", "REACTIONS", "RETIRED");
        when(types.findByEntityCodeAndCode("REACTIONS", "LIKE"))
                .thenReturn(Optional.of(retired));
        when(types.findById(TYPE_ID)).thenReturn(Optional.of(retired));

        assertThat(kinds.findLive("LIKE")).isEmpty();
        assertThat(kinds.findById(TYPE_ID)).map(HostType::code).hasValue("LIKE");
    }

    @Test
    void anAbsentCodeAndABlankCodeAreRefused() {
        when(types.findByEntityCodeAndCode("REACTIONS", "ANGRY")).thenReturn(Optional.empty());

        assertThat(kinds.findLive("ANGRY")).isEmpty();
        assertThat(kinds.findLive(" ")).isEmpty();
        assertThat(kinds.findLive(null)).isEmpty();
        assertThat(kinds.findById(null)).isEmpty();
    }

    @Test
    void aTypeFromAnotherNamespaceNeverResolvesAsAReaction() {
        when(types.findById(TYPE_ID))
                .thenReturn(Optional.of(type("HOUSE", "House", "PROPERTY", "ACTIVE")));

        assertThat(kinds.findById(TYPE_ID)).isEmpty();
    }

    @Test
    void theOfferedListIsTheCataloguesActiveRows() {
        when(types.findByEntityCodeAndStatusCodeOrderByCodeAsc("REACTIONS", "ACTIVE"))
                .thenReturn(List.of(type("LIKE", "Like", "REACTIONS", "ACTIVE"),
                        type("LOVE", "Love", "REACTIONS", "ACTIVE")));

        assertThat(kinds.live()).extracting(HostType::code)
                .containsExactly("LIKE", "LOVE");
        assertThat(kinds.live()).extracting(HostType::label)
                .containsExactly("Like", "Love");
    }

    private static Type type(String code, String label, String entityCode, String statusCode) {
        Entities entity = new Entities();
        entity.setCode(entityCode);
        Status status = new Status();
        status.setCode(statusCode);
        Type type = new Type();
        type.setId(TYPE_ID);
        type.setCode(code);
        type.setLabel(label);
        type.setEntity(entity);
        type.setStatus(status);
        return type;
    }
}
