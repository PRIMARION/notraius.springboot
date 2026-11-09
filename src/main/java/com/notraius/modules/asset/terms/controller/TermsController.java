package com.notraius.modules.asset.terms.controller;

import com.notraius.common.dto.bulk.BulkIdsRequest;
import com.notraius.common.dto.bulk.BulkItemsRequest;
import com.notraius.common.dto.bulk.BulkResponse;
import com.notraius.common.dto.responses.RevisionSummaryResponse;
import com.notraius.common.services.BulkRunner;
import com.notraius.config.web.PublicReadThrottle;
import com.notraius.modules.core.common.constants.NotraiusActivities;
import com.notraius.modules.asset.terms.dto.request.PolicyValueSetRequest;
import com.notraius.modules.asset.terms.dto.request.RuleSelectRequest;
import com.notraius.modules.asset.terms.dto.response.PolicyValueResponse;
import com.notraius.modules.asset.terms.dto.response.RuleSelectionResponse;
import com.notraius.modules.asset.terms.service.TermsService;
import jakarta.validation.Valid;
import org.primarion.module.utils.security.SecurityContextUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The asset module's terms sub-module (PLAN.md 4.3).
 * <p>
 * Two families on one theme — what the property commits to beyond its price:
 * <b>values</b> (the owner's number inside an admin standard, attached to a
 * pricing because the pricing supplies the unit) and <b>rule selections</b>
 * (the owner's promises, held for a window and forgettable).
 * <p>
 * Reads travel with the property: a published property shows its terms to
 * anyone, a draft only to its owner. Writes are always the owner's — an admin
 * defines the standards and the rules, never the property's answers to them.
 * <p>
 * <b>The three reads are public doors</b> and therefore carry no
 * {@code @PreAuthorize}: a logged-out visitor has no principal for an authority
 * gate to resolve, so the handler's {@code UNAUTHENTICATED} declaration is
 * the gate and the property's own visibility decides the answer. Each call
 * pays an IP-scoped {@link PublicReadThrottle} hit.
 */
@RestController
@RequestMapping("/api/v1/notraius/asset")
public class TermsController {

    private final TermsService termsService;
    private final BulkRunner bulkRunner;
    private final PublicReadThrottle publicReadThrottle;

    public TermsController(TermsService termsService, BulkRunner bulkRunner,
                           PublicReadThrottle publicReadThrottle) {
        this.termsService = termsService;
        this.bulkRunner = bulkRunner;
        this.publicReadThrottle = publicReadThrottle;
    }

    // ── Policy values ─────────────────────────────────────────────────────

    /** The values one pricing carries, each with its standard's bounds. */
    @GetMapping("/pricings/{pricingId}/values")
    @PreAuthorize("@authz.authority('UNAUTHENTICATED', 'GTE', 'PUBLIC', 'TERMS_LIST')")
    public ResponseEntity<List<PolicyValueResponse>> listValues(@PathVariable UUID pricingId) {
        publicReadThrottle.charge(NotraiusActivities.Terms.POLICY_VALUE_LIST);
        return ResponseEntity.ok(termsService.listValues(pricingId,
                SecurityContextUtils.getCurrentUserId()));
    }

    /**
     * One value, or the value as revision {@code version} stated it.
     * <p>
     * A read travels with the property, so this door has no {@code @PreAuthorize}:
     * the handler's {@code UNAUTHENTICATED} declaration is the gate and the
     * parent property's visibility decides the answer (a draft answers 404).
     */
    @GetMapping("/pricings/{pricingId}/values/{policyId}")
    @PreAuthorize("@authz.authority('UNAUTHENTICATED', 'GTE', 'PUBLIC', 'TERMS_GET')")
    public ResponseEntity<PolicyValueResponse> getValue(
            @PathVariable UUID pricingId,
            @PathVariable UUID policyId,
            @RequestParam(required = false) Integer version) {
        publicReadThrottle.charge(NotraiusActivities.Terms.POLICY_VALUE_LIST);
        return ResponseEntity.ok(termsService.getValue(pricingId, policyId, version,
                SecurityContextUtils.getCurrentUserId()));
    }

    /** Which revisions one value has had — discovery for {@code ?version=}. */
    @GetMapping("/pricings/{pricingId}/values/{policyId}/revisions")
    @PreAuthorize("@authz.authority('UNAUTHENTICATED', 'GTE', 'PUBLIC', 'TERMS_REVISIONS')")
    public ResponseEntity<List<RevisionSummaryResponse>> listValueRevisions(
            @PathVariable UUID pricingId, @PathVariable UUID policyId) {
        publicReadThrottle.charge(NotraiusActivities.Terms.POLICY_VALUE_LIST);
        return ResponseEntity.ok(termsService.listValueRevisions(pricingId, policyId,
                SecurityContextUtils.getCurrentUserId()));
    }

    /** Set the owner's number against one standard. */
    @PutMapping("/pricings/{pricingId}/values")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY_POLICIES', 'SET')")
    public ResponseEntity<PolicyValueResponse> setValue(@PathVariable UUID pricingId,
                                                       @Valid @RequestBody PolicyValueSetRequest request) {
        return ResponseEntity.ok(termsService.setValue(pricingId, request,
                SecurityContextUtils.getCurrentUserId()));
    }

    /**
     * Forget the owner's number again - the escape PLAN.md 4.3 promises: a
     * pricing whose duration is refused by a counting value becomes editable
     * once the owner removes the number that holds it.
     */
    @DeleteMapping("/pricings/{pricingId}/values/{policyId}")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY_POLICIES', 'FORGET')")
    public ResponseEntity<Void> forgetValue(@PathVariable UUID pricingId,
                                            @PathVariable UUID policyId) {
        termsService.forgetValue(pricingId, policyId, SecurityContextUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    // ── Rule selections ───────────────────────────────────────────────────

    @GetMapping("/properties/{propertyId}/rules")
    @PreAuthorize("@authz.authority('UNAUTHENTICATED', 'GTE', 'PUBLIC', 'TERMS_RULES')")
    public ResponseEntity<List<RuleSelectionResponse>> listRuleSelections(@PathVariable UUID propertyId) {
        publicReadThrottle.charge(NotraiusActivities.Terms.RULE_LIST);
        return ResponseEntity.ok(termsService.listRuleSelections(propertyId,
                SecurityContextUtils.getCurrentUserId()));
    }

    /**
     * One selection, or the selection as revision {@code version} stated it.
     * <p>
     * Authorized by the property, like the list: a selection travels with the
     * property's visibility and a past one can name a rule since forgotten.
     */
    @GetMapping("/properties/{propertyId}/rules/{selectionId}")
    @PreAuthorize("@authz.authority('UNAUTHENTICATED', 'GTE', 'PUBLIC', 'TERMS_RULE_GET')")
    public ResponseEntity<RuleSelectionResponse> getRuleSelection(
            @PathVariable UUID propertyId,
            @PathVariable UUID selectionId,
            @RequestParam(required = false) Integer version) {
        publicReadThrottle.charge(NotraiusActivities.Terms.RULE_LIST);
        return ResponseEntity.ok(termsService.getRuleSelection(propertyId, selectionId, version,
                SecurityContextUtils.getCurrentUserId()));
    }

    /** Which revisions one selection has had — discovery for {@code ?version=}. */
    @GetMapping("/properties/{propertyId}/rules/{selectionId}/revisions")
    @PreAuthorize("@authz.authority('UNAUTHENTICATED', 'GTE', 'PUBLIC', 'TERMS_RULE_REVISIONS')")
    public ResponseEntity<List<RevisionSummaryResponse>> listRuleSelectionRevisions(
            @PathVariable UUID propertyId, @PathVariable UUID selectionId) {
        publicReadThrottle.charge(NotraiusActivities.Terms.RULE_LIST);
        return ResponseEntity.ok(termsService.listRuleSelectionRevisions(propertyId, selectionId,
                SecurityContextUtils.getCurrentUserId()));
    }

    /** Select a rule (own or public) with its own window. */
    @PostMapping("/properties/{propertyId}/rules")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY_RULES', 'SELECT')")
    public ResponseEntity<RuleSelectionResponse> selectRule(@PathVariable UUID propertyId,
                                                           @Valid @RequestBody RuleSelectRequest request) {
        RuleSelectionResponse response = termsService.selectRule(propertyId, request,
                SecurityContextUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** Forget a selection the property holds — the row and its window go together. */
    @DeleteMapping("/properties/{propertyId}/rules/{selectionId}")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY_RULES', 'FORGET')")
    public ResponseEntity<Void> forgetRule(@PathVariable UUID propertyId,
                                          @PathVariable UUID selectionId) {
        termsService.forgetRule(propertyId, selectionId, SecurityContextUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Bulk doors — terms are a set, so they are set as a set
    // ═══════════════════════════════════════════════════════════════════════
    // The two families are naturally list-shaped: a pricing carries several
    // standards' numbers and a property carries several rules' promises. Each
    // item still runs the single-item door (owner check, bounds check, the
    // rule's usability check), in its own transaction.

    /** The values many pricings carry — one read per pricing, one answer each. */
    @GetMapping("/pricings/values/batch")
    @PreAuthorize("@authz.authority('UNAUTHENTICATED', 'GTE', 'PUBLIC', 'TERMS_BATCH')")
    public ResponseEntity<BulkResponse<List<PolicyValueResponse>>> batchListValues(
            @RequestParam List<UUID> pricingIds) {
        publicReadThrottle.charge(NotraiusActivities.Terms.POLICY_VALUE_LIST);
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.read(BulkIdsRequest.checked(pricingIds),
                id -> termsService.listValues(id, callerId)));
    }

    /** Set many of a pricing's numbers in one round trip. */
    @PutMapping("/pricings/{pricingId}/values/bulk")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY_POLICIES', 'SET')")
    public ResponseEntity<BulkResponse<PolicyValueResponse>> bulkSetValues(
            @PathVariable UUID pricingId,
            @Valid @RequestBody BulkItemsRequest<PolicyValueSetRequest> request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.runPayloads(request.getItems(),
                item -> termsService.setValue(pricingId, item, callerId)));
    }

    /** Forget many of a pricing's numbers in one round trip. */
    @DeleteMapping("/pricings/{pricingId}/values/bulk")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY_POLICIES', 'FORGET')")
    public ResponseEntity<BulkResponse<Void>> bulkForgetValues(
            @PathVariable UUID pricingId, @RequestParam List<UUID> policyIds) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(BulkIdsRequest.checked(policyIds), policyId -> {
            termsService.forgetValue(pricingId, policyId, callerId);
            return null;
        }));
    }

    /** Select many rules for one property — the selection screen saves once. */
    @PostMapping("/properties/{propertyId}/rules/bulk")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY_RULES', 'SELECT')")
    public ResponseEntity<BulkResponse<RuleSelectionResponse>> bulkSelectRules(
            @PathVariable UUID propertyId,
            @Valid @RequestBody BulkItemsRequest<RuleSelectRequest> request) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bulkRunner.runPayloads(request.getItems(),
                        item -> termsService.selectRule(propertyId, item, callerId)));
    }

    /** Forget many selections; each one's window goes with its row. */
    @DeleteMapping("/properties/{propertyId}/rules/bulk")
    @PreAuthorize("@authz.authority('PERSONAL', 'GTE', 'PROPERTY_RULES', 'FORGET')")
    public ResponseEntity<BulkResponse<Void>> bulkForgetRules(
            @PathVariable UUID propertyId, @RequestParam List<UUID> ids) {
        UUID callerId = SecurityContextUtils.getCurrentUserId();
        return ResponseEntity.ok(bulkRunner.run(BulkIdsRequest.checked(ids), id -> {
            termsService.forgetRule(propertyId, id, callerId);
            return null;
        }));
    }
}
