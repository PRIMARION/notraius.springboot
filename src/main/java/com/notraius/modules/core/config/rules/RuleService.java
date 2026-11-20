package com.notraius.modules.core.config.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.services.visit.QueryFilters;
import com.notraius.common.services.visit.RevisionService;
import com.notraius.common.services.logs.StatusResolver;
import com.notraius.database.entities.asset.config.Rule;
import com.notraius.database.entities.asset.config.RuleVisibility;
import com.notraius.database.entities.lease.reference.amenity.Entities;
import com.notraius.database.repositories.asset.asset.PropertyRuleSelectionRepository;
import com.notraius.database.repositories.asset.config.RuleRepository;
import com.notraius.modules.core.config.rules.dto.request.RuleCreateRequest;
import com.notraius.modules.core.config.rules.dto.request.RuleUpdateRequest;
import com.notraius.modules.core.config.rules.dto.request.RuleVisibilityRequest;
import com.notraius.modules.core.config.rules.dto.RuleResponse;
import com.notraius.modules.core.config.rules.RuleMapper;
import com.notraius.modules.core.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import lombok.extern.slf4j.Slf4j;
import com.notraius.common.dto.RevisionSummaryResponse;
import org.primarion.module.app.access.consent.consent.service.subscription.OwnershipGuard;
import org.primarion.module.communication.errors.client.BadRequestException;
import org.primarion.module.communication.errors.client.ConflictException;
import org.primarion.module.communication.errors.client.NotFoundException;
import org.primarion.module.utils.utils.security.security.SecurityAuthorityUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import com.notraius.common.audit.RequiredAuditWriter;
import com.notraius.database.entities.lease.reference.status.Status;

/**
 * The config module's rules sub-module (PLAN.md 3.1).
 * <p>
 * <b>Two lights, no third.</b> A rule is PUBLIC (every authenticated user reads
 * and selects it) or PRIVATE (its creator alone, minus the two documented
 * exceptions: the property that already holds the selection shows the rule
 * through its own listing, and staff read it for moderation — read authority,
 * never adoption).
 * <p>
 * <b>Versioning is the storage of record for history.</b> The row holds only
 * the newest truth, so every write bumps {@code version} and writes its
 * before/after entry in the same transaction: a version without history, or
 * history without a version, is impossible. Birth is version one and is itself
 * the first entry, so replay always has a base.
 * <p>
 * <b>The in-use lock.</b> Once any live property selection references the
 * rule, DISABLE is refused — but editing still works (properties reference the
 * rule, not a snapshot). When the last selection goes away the lock lifts, so
 * it is a live count, not a ratchet. There is no delete path at all: deletion
 * would break reconstitution, which is why this service exposes no
 * {@code delete}.
 * <p>
 * <b>Both reference questions are live</b>, asked against
 * {@code property.rules}:
 * <ul>
 *   <li>{@code disable}/{@code retire} — the in-use lock ("does <i>anyone</i>
 *       reference it");</li>
 *   <li>{@code changeVisibility} — the orphan test ("does anyone <i>else</i>
 *       reference it"), a deliberately different question.</li>
 * </ul>
 */
@Service
@Slf4j
public class RuleService extends OwnershipGuard {

    private final RuleRepository ruleRepository;
    private final PropertyRuleSelectionRepository selectionRepository;
    private final StatusResolver statusResolver;
    private final RuleMapper ruleMapper;
    private final AuditTrail auditTrail;
    private final QueryFilters queryFilters;
    private final RevisionService revisionService;

    public RuleService(RuleRepository ruleRepository,
                       PropertyRuleSelectionRepository selectionRepository,
                       StatusResolver statusResolver,
                       RuleMapper ruleMapper,
                       AuditTrail auditTrail,
                       QueryFilters queryFilters,
                       RevisionService revisionService) {
        this.ruleRepository = ruleRepository;
        this.selectionRepository = selectionRepository;
        this.statusResolver = statusResolver;
        this.ruleMapper = ruleMapper;
        this.auditTrail = auditTrail;
        this.queryFilters = queryFilters;
        this.revisionService = revisionService;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Create and read
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Create a rule. Any user, and the visibility is chosen here at birth —
     * the creator states which world the rule lives in.
     * <p>
     * Birth is version one <b>and</b> the first history entry: the audit write
     * below is the base replay starts from, committed in this same transaction.
     */
    @Transactional
    public RuleResponse createRule(RuleCreateRequest request, UUID userId) {
        RuleVisibility visibility = RuleVisibility.from(request.getVisibility());
        if (visibility == null) {
            throw new BadRequestException(NotraiusErrors.RULE_VISIBILITY_INVALID,
                    "Unknown rule visibility: " + request.getVisibility());
        }

        Rule rule = new Rule();
        rule.setCreatorId(userId);
        rule.setCreatedBy(userId);
        rule.setTitle(request.getTitle().trim());
        rule.setDescription(request.getDescription().trim());
        rule.setVisibility(visibility);
        rule.setVersion(1);
        rule.setStatus(statusResolver.resolve(Entities.RULES, NotraiusStatuses.RULE_ACTIVE));

        ruleRepository.save(rule);
        log.info("Rule created: id={} visibility={} creator={}", rule.getId(), visibility, userId);
        audit(NotraiusActivities.Rules.CREATE, rule.getId(), "CREATE", ruleMapper.toResponse(rule), null);
        return ruleMapper.toResponse(rule);
    }

    /**
     * The catalogue page, scoped by the visibility invariant in SQL.
     * <p>
     * A caller sees PUBLIC rules plus their own; staff see everything for
     * moderation. {@code ownOnly} narrows to the caller's own rules (both
     * visibilities), which is the "my rules" list. Status and text filters are
     * applied to that already-scoped set, never after it — a PRIVATE rule can
     * not leak through a count or a suggestion.
     */
    @Transactional(readOnly = true)
    public Page<RuleResponse> listRules(UUID callerId, boolean ownOnly, String statusCode,
                                        String visibility, String search, Pageable pageable) {
        RuleVisibility parsed = null;
        if (visibility != null && !visibility.isBlank()) {
            parsed = RuleVisibility.from(visibility);
            if (parsed == null) {
                throw new BadRequestException(NotraiusErrors.RULE_VISIBILITY_INVALID,
                        "Unknown rule visibility: " + visibility);
            }
        }
        boolean staff = SecurityAuthorityUtils.currentIsStaff();
        return ruleRepository.search(callerId, staff, ownOnly, queryFilters.status(Entities.RULES, statusCode),
                        parsed, queryFilters.search(search), pageable)
                .map(ruleMapper::toResponse);
    }

    /**
     * Read one rule — optionally as one version stated it.
     * <p>
     * A PRIVATE rule that the caller may not see answers <b>not found</b>
     * rather than forbidden: a 403 would confirm the row exists, which is
     * exactly the leak the visibility rule forbids. History follows the same
     * two lights — the creator, and staff for moderation — because a past
     * revision can hold text the rule has since withdrawn.
     */
    @Transactional(readOnly = true)
    public RuleResponse getRule(UUID ruleId, UUID callerId) {
        return getRule(ruleId, callerId, null);
    }

    @Transactional(readOnly = true)
    public RuleResponse getRule(UUID ruleId, UUID callerId, Integer version) {
        Rule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.RULE_NOT_FOUND,
                        "Rule not found: " + ruleId));
        if (!isVisibleTo(rule, callerId)) {
            throw new NotFoundException(NotraiusErrors.RULE_NOT_FOUND, "Rule not found: " + ruleId);
        }
        if (version == null || version.equals(rule.getVersion())) {
            return ruleMapper.toResponse(rule);
        }
        if (!isOwnerOrStaff(rule, callerId)) {
            throw new NotFoundException(NotraiusErrors.RULE_NOT_FOUND, "Rule not found: " + ruleId);
        }
        return revisionService.revisionOf(Entities.RULES, ruleId, version, RuleResponse.class);
    }

    /**
     * Which revisions of this rule the chain holds — discovery for the same
     * audience as a single revision (the creator, and staff for moderation).
     * A PRIVATE rule the caller may not see is answered as not found here too:
     * a list of versions would otherwise confirm the row exists.
     */
    @Transactional(readOnly = true)
    public List<RevisionSummaryResponse> listRevisions(UUID ruleId, UUID callerId) {
        Rule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.RULE_NOT_FOUND,
                        "Rule not found: " + ruleId));
        if (!isVisibleTo(rule, callerId) || !isOwnerOrStaff(rule, callerId)) {
            throw new NotFoundException(NotraiusErrors.RULE_NOT_FOUND, "Rule not found: " + ruleId);
        }
        return revisionService.revisionsOf(Entities.RULES, ruleId, rule.getVersion());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Edit and version
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Edit the text. Creator only — staff moderation is read authority, so
     * even an admin may not rewrite someone's rule.
     * <p>
     * The version bump and its before/after entry commit together, which is
     * what makes "the rule as of version three" answerable from the chain: the
     * entry carries the whole previous truth (title, description, visibility,
     * status, version).
     */
    @Transactional
    public RuleResponse updateRule(UUID ruleId, RuleUpdateRequest request, UUID callerId) {
        Rule rule = requireOwnedRule(ruleId, callerId);

        RuleResponse before = ruleMapper.toResponse(rule);
        boolean changed = false;
        if (request.getTitle() != null) {
            rule.setTitle(request.getTitle().trim());
            changed = true;
        }
        if (request.getDescription() != null) {
            rule.setDescription(request.getDescription().trim());
            changed = true;
        }
        if (!changed) {
            return before;
        }

        rule.setVersion(rule.getVersion() + 1);
        ruleRepository.save(rule);

        RuleResponse after = ruleMapper.toResponse(rule);
        audit(NotraiusActivities.Rules.UPDATE, ruleId, "UPDATE", after, before);
        log.info("Rule edited: id={} version={}", ruleId, after.getVersion());
        return after;
    }

    /**
     * Flip the visibility. Creator only.
     * <p>
     * PRIVATE → PUBLIC always: sharing more only adds readers, so it is the
     * creator's sovereignty. PUBLIC → PRIVATE is the dangerous direction and
     * is refused while <i>another person's</i> property still references the
     * rule (the orphan test), allowed when only the creator's own properties do
     * (the creator is the only one affected).
     * <p>
     * The gate counts selections held by properties of <i>other</i> owners —
     * the creator's own properties are the creator's to affect.
     */
    @Transactional
    public RuleResponse changeVisibility(UUID ruleId, RuleVisibilityRequest request, UUID callerId) {
        Rule rule = requireOwnedRule(ruleId, callerId);

        RuleVisibility target = RuleVisibility.from(request.getVisibility());
        if (target == null) {
            throw new BadRequestException(NotraiusErrors.RULE_VISIBILITY_INVALID,
                    "Unknown rule visibility: " + request.getVisibility());
        }
        if (target == rule.getVisibility()) {
            return ruleMapper.toResponse(rule);
        }
        if (target == RuleVisibility.PRIVATE && referencedBySomeoneElse(rule)) {
            throw new ConflictException(NotraiusErrors.RULE_VISIBILITY_ORPHANS,
                    "Another user's property selects rule " + ruleId + "; it cannot be made private");
        }

        RuleResponse before = ruleMapper.toResponse(rule);
        rule.setVisibility(target);
        rule.setVersion(rule.getVersion() + 1);
        ruleRepository.save(rule);

        RuleResponse after = ruleMapper.toResponse(rule);
        audit(NotraiusActivities.Rules.VISIBILITY, ruleId, "VISIBILITY", after, before);
        return after;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Status: the in-use lock
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Disable a rule. Creator only, ACTIVE only, and refused while any live
     * property selection references it — the sharper of the plan's two
     * reference tests, and deliberately eager: the safer failure is a lock that
     * is slightly too eager than a rule sliding to disabled under a living
     * property's feet.
     * <p>
     * The lock counts <i>any</i> live selection, the creator's own included:
     * a promise someone is relying on is not the author's to unmake.
     */
    @Transactional
    public RuleResponse disableRule(UUID ruleId, UUID callerId) {
        Rule rule = requireOwnedRule(ruleId, callerId);
        requireStatus(rule, NotraiusStatuses.RULE_ACTIVE, "disable");
        if (referencedByAnyone(rule)) {
            throw new ConflictException(NotraiusErrors.RULE_IN_USE,
                    "Rule " + ruleId + " is selected by a live property and cannot be disabled");
        }

        RuleResponse before = ruleMapper.toResponse(rule);
        rule.setStatus(statusResolver.resolve(Entities.RULES, NotraiusStatuses.RULE_DISABLED));
        // A status is a truth the row states, so the switch is a revision: the
        // chain must be able to answer "was this rule live in March?".
        rule.setVersion(rule.getVersion() + 1);
        ruleRepository.save(rule);

        RuleResponse after = ruleMapper.toResponse(rule);
        audit(NotraiusActivities.Rules.DISABLE, ruleId, "DISABLE", after, before);
        return after;
    }

    /** Switch it back on — disabling is always reversible once the lock has lifted. */
    @Transactional
    public RuleResponse enableRule(UUID ruleId, UUID callerId) {
        Rule rule = requireOwnedRule(ruleId, callerId);
        requireStatus(rule, NotraiusStatuses.RULE_DISABLED, "enable");

        RuleResponse before = ruleMapper.toResponse(rule);
        rule.setStatus(statusResolver.resolve(Entities.RULES, NotraiusStatuses.RULE_ACTIVE));
        rule.setVersion(rule.getVersion() + 1);
        ruleRepository.save(rule);

        RuleResponse after = ruleMapper.toResponse(rule);
        audit(NotraiusActivities.Rules.ENABLE, ruleId, "ENABLE", after, before);
        return after;
    }

    /**
     * Retire a rule for good — the exit taken once no property holds it any
     * more, and the reason the module has no delete: the row must stay for
     * history to remain replayable.
     */
    @Transactional
    public RuleResponse retireRule(UUID ruleId, UUID callerId) {
        Rule rule = requireOwnedRule(ruleId, callerId);
        if (NotraiusStatuses.RULE_RETIRED.equals(statusCodeOf(rule))) {
            return ruleMapper.toResponse(rule);
        }
        requireStatusIn(rule, "retire", NotraiusStatuses.RULE_ACTIVE, NotraiusStatuses.RULE_DISABLED);
        if (referencedByAnyone(rule)) {
            throw new ConflictException(NotraiusErrors.RULE_IN_USE,
                    "Rule " + ruleId + " is selected by a live property and cannot be retired");
        }

        RuleResponse before = ruleMapper.toResponse(rule);
        rule.setStatus(statusResolver.resolve(Entities.RULES, NotraiusStatuses.RULE_RETIRED));
        rule.setVersion(rule.getVersion() + 1);
        ruleRepository.save(rule);

        RuleResponse after = ruleMapper.toResponse(rule);
        audit(NotraiusActivities.Rules.RETIRE, ruleId, "RETIRE", after, before);
        return after;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // OwnershipGuard — "did this caller create the rule?"
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * args[0]: the rule, args[1]: the caller id. A rule's only owner is its
     * creator; staff moderation never becomes authorship.
     */
    @Override
    public boolean canAccess(Object... args) {
        if (args.length < 2) {
            return false;
        }
        if (!(args[0] instanceof Rule rule) || !(args[1] instanceof UUID callerId)) {
            return false;
        }
        return callerId != null && callerId.equals(rule.getCreatorId());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private Rule requireOwnedRule(UUID ruleId, UUID callerId) {
        Rule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.RULE_NOT_FOUND,
                        "Rule not found: " + ruleId));
        requireOwnership("Only the rule's creator may change it", rule, callerId);
        return rule;
    }

    /** PUBLIC, own, or staff — the three ways a rule becomes readable. */
    private boolean isVisibleTo(Rule rule, UUID callerId) {
        if (rule.getVisibility() == RuleVisibility.PUBLIC) {
            return true;
        }
        if (callerId != null && callerId.equals(rule.getCreatorId())) {
            return true;
        }
        return SecurityAuthorityUtils.currentIsStaff();
    }

    /** The two rungs that may read a rule's past: authorship and moderation. */
    private boolean isOwnerOrStaff(Rule rule, UUID callerId) {
        return (callerId != null && callerId.equals(rule.getCreatorId()))
                || SecurityAuthorityUtils.currentIsStaff();
    }

    /** The orphan test: does anyone OTHER than the creator rely on this rule? */
    /** The orphan test: does another owner's property select this rule? */
    private boolean referencedBySomeoneElse(Rule rule) {
        return selectionRepository.existsByRuleIdAndPropertyOwnerIdNot(rule.getId(), rule.getCreatorId());
    }

    /**
     * The in-use lock: a rule a property holds cannot be switched off or retired
     * underneath it. Only forgetting the selection releases the rule, and
     * forgetting is the property owner's act — so the rule's own author cannot
     * unilaterally disable a promise someone is relying on.
     */
    private boolean referencedByAnyone(Rule rule) {
        return selectionRepository.existsByRuleId(rule.getId());
    }

    private void requireStatus(Rule rule, String expected, String operation) {
        String actual = statusCodeOf(rule);
        if (!expected.equals(actual)) {
            throw new ConflictException(NotraiusErrors.RULE_INVALID_STATE,
                    "Rule is " + actual + "; " + operation + " requires " + expected);
        }
    }

    private void requireStatusIn(Rule rule, String operation, String... allowed) {
        String actual = statusCodeOf(rule);
        for (String candidate : allowed) {
            if (candidate.equals(actual)) {
                return;
            }
        }
        throw new ConflictException(NotraiusErrors.RULE_INVALID_STATE,
                "Rule is " + actual + "; " + operation + " requires one of " + String.join(", ", allowed));
    }

    private String statusCodeOf(Rule rule) {
        return rule.getStatus() != null ? rule.getStatus().getCode() : null;
    }

    /**
     * Append one history entry carrying the before and after snapshots — each
     * snapshot states its own version, which is what makes the chain
     * replayable. Fail-soft: an audit row must never roll back the version bump
     * it records; in the normal case both commit together, because both are in
     * this transaction.
     */
    /**
     * A rule's history is a read model, not a shadow: the entry is written
     * through {@link RequiredAuditWriter#writeRequired}, so a failure rolls the
     * version bump back with it.
     */
    private void audit(String activityCode, UUID referenceId, String operation,
                       Object after, Object before) {
        auditTrail.recordRequired(Entities.RULES, activityCode, referenceId, operation, before, after);
    }
}
