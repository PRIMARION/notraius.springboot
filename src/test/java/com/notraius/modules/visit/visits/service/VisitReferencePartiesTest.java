package com.notraius.modules.visit.visits.service;

import com.notraius.database.entities.visit.Visit;
import com.notraius.database.repositories.visit.VisitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The visit's party circle (5.5): all three attached people are parties — the
 * visitor who paid, the counterpart who was stamped, the owner whose money is at
 * stake — and nobody else is.
 */
@ExtendWith(MockitoExtension.class)
class VisitReferencePartiesTest {

    private static final UUID VISITOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ASSIGNEE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID STRANGER = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock private VisitRepository visitRepository;

    private VisitService service;

    @BeforeEach
    void setUp() {
        service = new VisitServiceFixture()
                .visitRepository(visitRepository)
                .build();
    }

    @Test
    @DisplayName("the resolver speaks for the visits entity")
    void entityCodeIsVisits() {
        assertThat(service.entityCode()).isEqualTo("VISITS");
    }

    @Test
    @DisplayName("visitor, counterpart and owner are all inside the circle")
    void allThreeParties() {
        Visit visit = visitWith(true);
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThat(service.isParty(visit.getId(), VISITOR)).isTrue();
        assertThat(service.isParty(visit.getId(), ASSIGNEE)).isTrue();
        assertThat(service.isParty(visit.getId(), OWNER)).isTrue();
    }

    @Test
    @DisplayName("an outsider is not a party to somebody else's visit")
    void strangerIsOutside() {
        Visit visit = visitWith(true);
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThat(service.isParty(visit.getId(), STRANGER)).isFalse();
    }

    @Test
    @DisplayName("a visit with no counterpart yet still has its visitor and owner")
    void unassignedVisitStillHasTwoParties() {
        Visit visit = visitWith(false);
        when(visitRepository.findById(visit.getId())).thenReturn(Optional.of(visit));

        assertThat(service.isParty(visit.getId(), VISITOR)).isTrue();
        assertThat(service.isParty(visit.getId(), OWNER)).isTrue();
        assertThat(service.isParty(visit.getId(), null)).isFalse();
    }

    @Test
    @DisplayName("a reference nobody can resolve is nobody's party")
    void unknownReferenceIsNotAParty() {
        UUID unknown = UUID.randomUUID();
        when(visitRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThat(service.isParty(unknown, VISITOR)).isFalse();
        assertThat(service.isParty(null, VISITOR)).isFalse();
    }

    private Visit visitWith(boolean assigned) {
        Visit visit = new Visit();
        visit.setId(UUID.randomUUID());
        visit.setVisitorId(VISITOR);
        visit.setOwnerId(OWNER);
        visit.setUserId(assigned ? ASSIGNEE : null);
        return visit;
    }
}
