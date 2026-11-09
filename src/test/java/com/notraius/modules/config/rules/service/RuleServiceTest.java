package com.notraius.modules.config.rules.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.dto.responses.RevisionSummaryResponse;
import com.notraius.common.services.QueryFilters;
import com.notraius.common.services.RevisionService;
import com.notraius.common.services.StatusResolver;
import com.notraius.database.repositories.asset.PropertyRuleSelectionRepository;
import com.notraius.database.entities.config.Rule;
import com.notraius.database.entities.config.RuleVisibility;
import com.notraius.database.entities.reference.Entities;
import com.notraius.database.entities.reference.Status;
import com.notraius.database.repositories.config.RuleRepository;
import com.notraius.modules.config.rules.dto.request.RuleCreateRequest;
import com.notraius.modules.config.rules.dto.request.RuleUpdateRequest;
import com.notraius.modules.config.rules.dto.request.RuleVisibilityRequest;
import com.notraius.modules.config.rules.dto.response.RuleResponse;
import com.notraius.modules.config.rules.mapper.RuleMapper;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import com.notraius.common.audit.RequiredAuditWriter;
import org.primarion.module.errors.AppException;
import org.primarion.module.errors.ConflictException;
import org.primarion.module.errors.ForbiddenException;
import org.primarion.module.errors.NotFoundException;
import org.primarion.module.utils.security.SecurityAuthorityUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stress check for the rules sub-module (PLAN.md 3.1): the two-light
 * visibility invariant, versioning as the storage of record for history, and
 * the disable/enable status laws.
 * <p>
 * The two reference tests that need the property-selection table (the in-use
 * lock and the orphan test) are forward hooks — their tables do not exist in
 * this schema — so what is provable here is everything around them.
 */
@ExtendWith(MockitoExtension.class)
class RuleServiceTest {

    private static final UUID CREATOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STRANGER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private RuleRepository ruleRepository;
    @Mock private PropertyRuleSelectionRepository selectionRepository;
    @Mock private StatusResolver statusResolver;
    @Mock private RequiredAuditWriter auditLogWriter;
    @Mock private QueryFilters queryFilters;
    @Mock private RevisionService revisionService;

    private RuleService service;

    @BeforeEach
    void setUp() {
        service = new RuleService(ruleRepository, selectionRepository, statusResolver, new RuleMapper(),
                new AuditTrail(new ObjectMapper().findAndRegisterModules(), auditLogWriter), queryFilters, revisionService);
        lenient().when(statusResolver.resolve(any(), any()))
                .thenAnswer(invocation -> status(invocation.getArgument(1)));
        // Filter law is QueryFiltersTest's subject; pass-through keeps these
        // tests on the service's own decisions.
        lenient().when(queryFilters.status(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(queryFilters.search(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── Birth ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("birth is version one, ACTIVE, and the first history entry")
    void birthIsVersionOneAndLogged() {
        RuleResponse created = service.createRule(
                new RuleCreateRequest("No pets", "No pets on the premises", "private"), CREATOR);

        assertThat(created.getVersion()).isEqualTo(1);
        assertThat(created.getVisibility()).isEqualTo("PRIVATE");
        assertThat(created.getStatusCode()).isEqualTo(NotraiusStatuses.RULE_ACTIVE);
        assertThat(created.getCreatorId()).isEqualTo(CREATOR);
        verify(auditLogWriter).writeRequired(eq(Entities.RULES), eq("RULE.CREATE"), any(), eq("CREATE"),
                isNull(), any());
    }

    @Test
    @DisplayName("visibility has exactly two lights — there is no third")
    void unknownVisibilityIsRefused() {
        assertThatThrownBy(() -> service.createRule(
                new RuleCreateRequest("No pets", "No pets", "UNLISTED"), CREATOR))
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.RULE_VISIBILITY_INVALID));
        verify(ruleRepository, never()).save(any());
    }

    // ── Reading: the visibility invariant ─────────────────────────────────

    @Test
    @DisplayName("a PRIVATE rule answers not-found to a stranger — a 403 would confirm it exists")
    void privateRuleIsInvisibleToStrangers() {
        Rule rule = rule(RuleVisibility.PRIVATE, CREATOR, NotraiusStatuses.RULE_ACTIVE);
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));

        assertThatThrownBy(() -> service.getRule(rule.getId(), STRANGER))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("its creator reads a PRIVATE rule")
    void privateRuleIsReadableByItsCreator() {
        Rule rule = rule(RuleVisibility.PRIVATE, CREATOR, NotraiusStatuses.RULE_ACTIVE);
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));

        assertThat(service.getRule(rule.getId(), CREATOR).getVisibility()).isEqualTo("PRIVATE");
    }

    @Test
    @DisplayName("a PUBLIC rule is readable by every authenticated user")
    void publicRuleIsReadableByAnyone() {
        Rule rule = rule(RuleVisibility.PUBLIC, CREATOR, NotraiusStatuses.RULE_ACTIVE);
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));

        assertThat(service.getRule(rule.getId(), STRANGER).getId()).isEqualTo(rule.getId());
    }

    @Test
    @DisplayName("staff read a PRIVATE rule for moderation — read authority, not adoption")
    void staffReadPrivateRules() {
        Rule rule = rule(RuleVisibility.PRIVATE, CREATOR, NotraiusStatuses.RULE_ACTIVE);
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));

        try (MockedStatic<SecurityAuthorityUtils> authorities = Mockito.mockStatic(SecurityAuthorityUtils.class)) {
            authorities.when(SecurityAuthorityUtils::currentIsStaff).thenReturn(true);
            assertThat(service.getRule(rule.getId(), STRANGER).getId()).isEqualTo(rule.getId());
        }
    }

    @Test
    @DisplayName("the listing is scoped in SQL — the staff flag and ownOnly reach the query")
    void listingPushesTheInvariantIntoTheQuery() {
        when(ruleRepository.search(eq(CREATOR), eq(false), eq(false), isNull(), isNull(), isNull(), any()))
                .thenReturn(org.springframework.data.domain.Page.<Rule>empty());

        service.listRules(CREATOR, false, null, null, null,
                org.springframework.data.domain.Pageable.unpaged());

        verify(ruleRepository).search(CREATOR, false, false, null, null, null,
                org.springframework.data.domain.Pageable.unpaged());
    }

    // ── Editing: every edit is a new truth ────────────────────────────────

    @Test
    @DisplayName("a stranger cannot edit the rule — staff moderation is not authorship")
    void strangersCannotEdit() {
        Rule rule = rule(RuleVisibility.PUBLIC, CREATOR, NotraiusStatuses.RULE_ACTIVE);
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));

        assertThatThrownBy(() -> service.updateRule(rule.getId(), new RuleUpdateRequest("New", null), STRANGER))
                .isInstanceOf(ForbiddenException.class);
        verify(ruleRepository, never()).save(any());
    }

    @Test
    @DisplayName("an accepted edit bumps the version and records before/after in the same transaction")
    void editsBumpTheVersionAndKeepHistory() {
        Rule rule = rule(RuleVisibility.PRIVATE, CREATOR, NotraiusStatuses.RULE_ACTIVE);
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));

        RuleResponse edited = service.updateRule(rule.getId(),
                new RuleUpdateRequest("No pets at all", "Stricter"), CREATOR);

        assertThat(edited.getVersion()).isEqualTo(2);
        assertThat(edited.getTitle()).isEqualTo("No pets at all");
        verify(auditLogWriter).writeRequired(eq(Entities.RULES), eq("RULE.UPDATE"), eq(rule.getId()), eq("UPDATE"),
                any(), any());
    }

    @Test
    @DisplayName("an edit that changes nothing is not a new truth")
    void emptyEditIsANoOp() {
        Rule rule = rule(RuleVisibility.PRIVATE, CREATOR, NotraiusStatuses.RULE_ACTIVE);
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));

        RuleResponse unchanged = service.updateRule(rule.getId(), new RuleUpdateRequest(null, null), CREATOR);

        assertThat(unchanged.getVersion()).isEqualTo(1);
        verify(ruleRepository, never()).save(any());
    }

    @Test
    @DisplayName("visibility flips are versioned too — the flip is a new truth")
    void visibilityFlipBumpsTheVersion() {
        Rule rule = rule(RuleVisibility.PRIVATE, CREATOR, NotraiusStatuses.RULE_ACTIVE);
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));

        RuleResponse flipped = service.changeVisibility(rule.getId(),
                new RuleVisibilityRequest("PUBLIC"), CREATOR);

        assertThat(flipped.getVisibility()).isEqualTo("PUBLIC");
        assertThat(flipped.getVersion()).isEqualTo(2);
        verify(auditLogWriter).writeRequired(eq(Entities.RULES), eq("RULE.VISIBILITY"), eq(rule.getId()),
                eq("VISIBILITY"), any(), any());
    }

    @Test
    @DisplayName("re-stating the current visibility changes nothing")
    void sameVisibilityIsANoOp() {
        Rule rule = rule(RuleVisibility.PUBLIC, CREATOR, NotraiusStatuses.RULE_ACTIVE);
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));

        service.changeVisibility(rule.getId(), new RuleVisibilityRequest("public"), CREATOR);

        assertThat(rule.getVersion()).isEqualTo(1);
        verify(ruleRepository, never()).save(any());
    }

    // ── Status ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("only an ACTIVE rule can be disabled")
    void disableRequiresActive() {
        Rule disabled = rule(RuleVisibility.PRIVATE, CREATOR, NotraiusStatuses.RULE_DISABLED);
        when(ruleRepository.findById(disabled.getId())).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.disableRule(disabled.getId(), CREATOR))
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.RULE_INVALID_STATE));
    }

    @Test
    @DisplayName("only a DISABLED rule can be re-enabled")
    void enableRequiresDisabled() {
        Rule active = rule(RuleVisibility.PRIVATE, CREATOR, NotraiusStatuses.RULE_ACTIVE);
        when(ruleRepository.findById(active.getId())).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.enableRule(active.getId(), CREATOR))
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.RULE_INVALID_STATE));
    }

    @Test
    @DisplayName("disable then enable is a round trip — disabling is always reversible")
    void disableAndEnableRoundTrip() {
        Rule rule = rule(RuleVisibility.PRIVATE, CREATOR, NotraiusStatuses.RULE_ACTIVE);
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));

        assertThat(service.disableRule(rule.getId(), CREATOR).getStatusCode())
                .isEqualTo(NotraiusStatuses.RULE_DISABLED);
        assertThat(service.enableRule(rule.getId(), CREATOR).getStatusCode())
                .isEqualTo(NotraiusStatuses.RULE_ACTIVE);
    }

    @Test
    @DisplayName("retiring an already retired rule is idempotent, never a delete")
    void retireIsIdempotent() {
        Rule retired = rule(RuleVisibility.PUBLIC, CREATOR, NotraiusStatuses.RULE_RETIRED);
        when(ruleRepository.findById(retired.getId())).thenReturn(Optional.of(retired));

        assertThat(service.retireRule(retired.getId(), CREATOR).getStatusCode())
                .isEqualTo(NotraiusStatuses.RULE_RETIRED);
        verify(ruleRepository, never()).save(any());
        verify(ruleRepository, never()).delete(any());
    }

    @Test
    @DisplayName("a rule a property already holds cannot be disabled underneath it")
    void heldRuleCannotBeDisabled() {
        Rule rule = rule(RuleVisibility.PUBLIC, CREATOR, NotraiusStatuses.RULE_ACTIVE);
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));
        when(selectionRepository.existsByRuleId(rule.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.disableRule(rule.getId(), CREATOR))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.RULE_IN_USE));
    }

    @Test
    @DisplayName("the same lock stops retirement: a selection must be forgotten first")
    void heldRuleCannotBeRetired() {
        Rule rule = rule(RuleVisibility.PUBLIC, CREATOR, NotraiusStatuses.RULE_ACTIVE);
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));
        when(selectionRepository.existsByRuleId(rule.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.retireRule(rule.getId(), CREATOR))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.RULE_IN_USE));
    }

    // ── The status switch is a revision, and history is readable ──────────

    @Test
    @DisplayName("disabling a rule bumps its version — 'was this rule live in March?' stays answerable")
    void disableBumpsVersion() {
        Rule rule = rule(RuleVisibility.PUBLIC, CREATOR, NotraiusStatuses.RULE_ACTIVE);
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));

        RuleResponse disabled = service.disableRule(rule.getId(), CREATOR);

        assertThat(disabled.getStatusCode()).isEqualTo(NotraiusStatuses.RULE_DISABLED);
        assertThat(disabled.getVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("re-enabling is the next revision, not a return to version one")
    void enableBumpsAgain() {
        Rule rule = rule(RuleVisibility.PUBLIC, CREATOR, NotraiusStatuses.RULE_DISABLED);
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));

        assertThat(service.enableRule(rule.getId(), CREATOR).getVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("the creator reads a superseded version from the audit chain")
    void creatorReadsPastRevision() {
        Rule rule = rule(RuleVisibility.PUBLIC, CREATOR, NotraiusStatuses.RULE_ACTIVE);
        rule.setVersion(3);
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));

        RuleResponse born = new RuleResponse();
        born.setId(rule.getId());
        born.setTitle("No pets (first draft)");
        born.setVersion(1);
        when(revisionService.revisionOf(Entities.RULES, rule.getId(), 1, RuleResponse.class))
                .thenReturn(born);

        assertThat(service.getRule(rule.getId(), CREATOR, 1).getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("even a PUBLIC rule's past is authorship-and-staff only: a stranger gets 404")
    void strangerCannotReadPublicRuleHistory() {
        Rule rule = rule(RuleVisibility.PUBLIC, CREATOR, NotraiusStatuses.RULE_ACTIVE);
        rule.setVersion(2);
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));

        assertThatThrownBy(() -> service.getRule(rule.getId(), STRANGER, 1))
                .isInstanceOf(NotFoundException.class);
        verify(revisionService, never()).revisionOf(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("the creator lists the revisions the chain holds for their rule")
    void creatorListsRevisions() {
        Rule rule = rule(RuleVisibility.PUBLIC, CREATOR, NotraiusStatuses.RULE_ACTIVE);
        rule.setVersion(2);
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));
        when(revisionService.revisionsOf(Entities.RULES, rule.getId(), 2)).thenReturn(List.of(
                new RevisionSummaryResponse(2, Instant.now(), true),
                new RevisionSummaryResponse(1, Instant.now().minusSeconds(60), false)));

        assertThat(service.listRevisions(rule.getId(), CREATOR))
                .extracting(RevisionSummaryResponse::getVersion)
                .containsExactly(2, 1);
    }

    @Test
    @DisplayName("a version list is not an existence oracle — a stranger gets 404 and no chain read")
    void strangerCannotListRevisions() {
        Rule rule = rule(RuleVisibility.PRIVATE, CREATOR, NotraiusStatuses.RULE_ACTIVE);
        rule.setVersion(2);
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));

        assertThatThrownBy(() -> service.listRevisions(rule.getId(), STRANGER))
                .isInstanceOf(NotFoundException.class);
        verify(revisionService, never()).revisionsOf(any(), any(), anyInt());
    }

    @Test
    @DisplayName("asking for the current version is the live read — no chain lookup")
    void currentVersionIsLiveRead() {
        Rule rule = rule(RuleVisibility.PUBLIC, CREATOR, NotraiusStatuses.RULE_ACTIVE);
        rule.setVersion(4);
        when(ruleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));

        assertThat(service.getRule(rule.getId(), CREATOR, 4).getTitle()).isEqualTo("No pets");
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private Rule rule(RuleVisibility visibility, UUID creator, String statusCode) {
        Rule rule = new Rule();
        rule.setId(UUID.randomUUID());
        rule.setCreatorId(creator);
        rule.setCreatedBy(creator);
        rule.setTitle("No pets");
        rule.setDescription("No pets on the premises");
        rule.setVisibility(visibility);
        rule.setVersion(1);
        rule.setStatus(status(statusCode));
        return rule;
    }

    private Status status(String code) {
        Status status = new Status();
        status.setId(UUID.randomUUID());
        status.setCode(code);
        return status;
    }

    /** The stable client contract is the error CODE, not the prose. */
    private static org.primarion.module.errors.ErrorCode errorOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(AppException.class);
        return ((AppException) thrown).getError();
    }
}
