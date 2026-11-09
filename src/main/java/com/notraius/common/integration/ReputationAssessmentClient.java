package com.notraius.common.integration;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.notraius.config.properties.InterServiceProperties;
import com.notraius.config.properties.NotraiusProperties;
import com.notraius.database.entities.reference.Entities;
import com.notraius.database.repositories.reference.EntitiesRepository;
import lombok.extern.slf4j.Slf4j;
import org.primarion.module.constants.Endpoint;
import org.primarion.module.constants.EndpointRegistry;
import org.primarion.module.utils.security.identity.InternalUserProperties;
import org.primarion.module.utils.security.users.ServiceAuthHeaders;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The reputation host's assessment doors, as visits use them (PLAN 5.3–5.4).
 * <p>
 * <b>notraius judges nobody itself and stores no judgment.</b> Assessments belong
 * to primarion, which owns the axis vocabulary, validates that every configured
 * axis is scored, and aggregates standing from the rows. This client is the
 * module's whole contact surface with that truth: submit one, list the ones a
 * visit has collected, and read the axis vocabulary when the platform must file
 * a judgment on its own (the no-show and cancellation laws file an all-axes
 * score of 1 — a legitimate bad review, worded in its reason, not a magic
 * number invented here).
 * <p>
 * <b>Delegation is declared, never assumed.</b> The host refuses an assessment
 * with no identifiable human author, so every call carries
 * {@code X-Session} naming the party the judgment belongs to: the assessing user
 * on the mutual path, the surviving party on the automatic ones. The platform
 * files <em>their</em> judgment; it never speaks with a voice of its own.
 * <p>
 * Provenance is notraius's own triple — the {@code VISITS} entity-registry id, the
 * visit's id as reference, and the activity id of the operation that produced the
 * judgment — so the host can resolve the record without mirroring notraius's
 * tables.
 */
@Component
@Slf4j
public class ReputationAssessmentClient {

    private static final String CREATE_ASSESSMENT = "primarion.assessment.createAssessment";
    private static final String LIST_ASSESSMENTS = "primarion.assessment.listAssessments";
    private static final String AXIS_SCORES = "primarion.reputation.axisScores";

    private static final int WALK_PAGE_SIZE = 100;

    /** The score of a fully negative judgment (PLAN 5.3/5.4: all axes at 1). */
    public static final BigDecimal NEGATIVE_AXIS_SCORE = BigDecimal.ONE;

    private final RestClient restClient;
    private final NotraiusProperties notraiusProperties;
    private final ServiceAuthHeaders serviceAuthHeaders;
    private final EndpointRegistry endpoints;
    private final EntitiesRef entitiesRef;

    public ReputationAssessmentClient(NotraiusProperties notraiusProperties,
                                      InternalUserProperties identityProperties,
                                      InterServiceProperties interServiceProperties,
                                      ServiceAuthHeaders serviceAuthHeaders,
                                      EndpointRegistry endpoints,
                                      RestClient.Builder restClientBuilder,
                                      EntitiesRef entitiesRef) {
        this.notraiusProperties = notraiusProperties;
        this.serviceAuthHeaders = serviceAuthHeaders;
        this.endpoints = endpoints;
        this.entitiesRef = entitiesRef;
        InterServiceProperties.Timeout timeout = interServiceProperties.getAssessment();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout.getConnectTimeoutMs());
        factory.setReadTimeout(timeout.getReadTimeoutMs());
        this.restClient = restClientBuilder.clone()
                .baseUrl(identityProperties.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * Submit one assessment of {@code targetUserId}, authored by
     * {@code authorUserId}. {@code activityCode} names the operation that
     * produced the judgment (e.g. {@code VISIT.ASSESS}); its suffix becomes the
     * recorded operation.
     */
    public AssessmentRef submit(UUID targetUserId, UUID authorUserId, UUID visitId,
                                String activityCode, UUID activityId,
                                String reason, Map<String, BigDecimal> axisScores) {
        return submitForEntity(targetUserId, authorUserId, entitiesRef.visitsEntityId(), visitId,
                activityCode, activityId, reason, axisScores);
    }

    /**
     * The same judgment door for a record other than a visit: the lease's
     * automatic miss assessment is bound to the installment's own row (6.3),
     * so its provenance is the {@code LEASE_BREAKDOWNS} registry id and that
     * breakdown's id. One client, one contract, no second vocabulary.
     */
    public AssessmentRef submitForEntity(UUID targetUserId, UUID authorUserId, UUID entityId,
                                         UUID referenceId, String activityCode, UUID activityId,
                                         String reason, Map<String, BigDecimal> axisScores) {
        String operation = activityCode.substring(activityCode.indexOf('.') + 1);
        SubmitRequest body = new SubmitRequest(entityId, referenceId, activityId,
                operation, authorUserId, reason, axisScores);
        return restClient.post()
                .uri(uri -> uri.path(path(CREATE_ASSESSMENT)).build(targetUserId))
                .headers(h -> h.setAll(serviceAuthHeaders.forTarget(
                        notraiusProperties.getPlatformTargetPrimarion(), authorUserId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(AssessmentRef.class);
    }

    /**
     * Every assessment a visit has collected, whoever authored it. The confirm
     * gate and the edit rules read exactly this list, so the judgment's state is
     * the host's, never a local flag that could disagree with it.
     */
    public List<AssessmentRef> listForVisit(UUID visitId) {
        List<AssessmentRef> assessments = new ArrayList<>();
        int page = 0;
        while (true) {
            final int current = page;
            PageEnvelope<AssessmentRef> envelope = restClient.get()
                    .uri(uri -> uri.path(path(LIST_ASSESSMENTS))
                            .queryParam("entityId", entitiesRef.visitsEntityId())
                            .queryParam("referenceId", visitId)
                            .queryParam("page", current)
                            .queryParam("size", WALK_PAGE_SIZE)
                            .build())
                    .headers(h -> h.setAll(serviceAuthHeaders.forTarget(
                            notraiusProperties.getPlatformTargetPrimarion())))
                    .retrieve()
                    .body(new ParameterizedTypeReference<PageEnvelope<AssessmentRef>>() {
                    });
            if (envelope == null || envelope.content() == null || envelope.content().isEmpty()) {
                return assessments;
            }
            assessments.addAll(envelope.content());
            if (!envelope.hasNext()) {
                return assessments;
            }
            page++;
        }
    }

    /**
     * The axis vocabulary, read from the host's own per-axis score surface. Every
     * configured axis answers there, scored or not, so this is the honest way to
     * learn the codes without mirroring the vocabulary.
     */
    public List<String> axisCodes(UUID forUserId) {
        List<AxisScore> scores = restClient.get()
                .uri(uri -> uri.path(path(AXIS_SCORES)).build(forUserId))
                .headers(h -> h.setAll(serviceAuthHeaders.forTarget(
                        notraiusProperties.getPlatformTargetPrimarion())))
                .retrieve()
                .body(new ParameterizedTypeReference<List<AxisScore>>() {
                });
        if (scores == null) {
            return List.of();
        }
        return scores.stream().map(AxisScore::axisCode).filter(code -> code != null && !code.isBlank()).toList();
    }

    /**
     * File the platform's own fully negative judgment — score 1 on every
     * configured axis — on behalf of the party it belongs to. The axis list is
     * read from the host first so a new axis added tomorrow is covered without a
     * change here; no axes at all means no vocabulary to judge against, and that
     * is a loud failure rather than a silent empty review.
     */
    public Optional<UUID> fileAllAxesNegative(UUID targetUserId, UUID authorUserId, UUID visitId,
                                              String activityCode, UUID activityId, String reason) {
        return fileAllAxesNegativeForEntity(targetUserId, authorUserId, entitiesRef.visitsEntityId(),
                visitId, activityCode, activityId, reason);
    }

    /**
     * The all-axes judgment for any provenance pair — the lease's automatic
     * assessment files against the breakdown it judges, never against the lease
     * as a whole ("which month was missed is on the assessment's face", 6.3).
     */
    public Optional<UUID> fileAllAxesNegativeForEntity(UUID targetUserId, UUID authorUserId,
                                                      UUID entityId, UUID referenceId,
                                                      String activityCode, UUID activityId,
                                                      String reason) {
        List<String> codes = axisCodes(targetUserId);
        if (codes.isEmpty()) {
            throw new IllegalStateException(
                    "The reputation host returned no axis vocabulary; cannot file the judgment on record "
                            + entityId + "/" + referenceId);
        }
        Map<String, BigDecimal> scores = new LinkedHashMap<>();
        for (String code : codes) {
            scores.put(code, NEGATIVE_AXIS_SCORE);
        }
        AssessmentRef created = submitForEntity(targetUserId, authorUserId, entityId, referenceId,
                activityCode, activityId, reason, scores);
        return created != null ? Optional.ofNullable(created.id()) : Optional.empty();
    }

    private String path(String key) {
        return endpoints.endpoint(key)
                .map(Endpoint::getEndpoint)
                .orElseThrow(() -> new IllegalStateException("Missing endpoint for key '" + key
                        + "' in this service's endpoints.yaml"));
    }

    // ── Wire shapes ───────────────────────────────────────────────────────

    public record AssessmentRef(UUID id, UUID entityId, UUID referenceId, UUID activityId,
                                UUID createdByUserId, BigDecimal score, String reason,
                                Map<String, BigDecimal> axisScores, Instant createdAt) {
    }

    /** The host's per-axis standing row — only the code matters here. */
    public record AxisScore(String axisCode, BigDecimal score) {
    }

    private record PageEnvelope<T>(List<T> content, boolean hasNext) {
    }

    private record SubmitRequest(UUID entityId, UUID referenceId, UUID activityId, String operation,
                                 UUID assessorUserId, String reason,
                                 @JsonAlias("axisScores") Map<String, BigDecimal> axisScores) {
    }

    /**
     * Resolves notraius's own {@code VISITS} registry id, cached per instance —
     * the provenance row lives in this service's own reference tables.
     */
    @Component
    public static class EntitiesRef {
        private final EntitiesRepository entitiesRepository;
        private volatile UUID visitsEntityId;
        private volatile UUID leaseBreakdownsEntityId;

        public EntitiesRef(EntitiesRepository entitiesRepository) {
            this.entitiesRepository = entitiesRepository;
        }

        public UUID visitsEntityId() {
            UUID id = visitsEntityId;
            if (id == null) {
                id = require(Entities.VISITS);
                visitsEntityId = id;
            }
            return id;
        }

        /** The lease's money-row registry id — the miss assessment's provenance. */
        public UUID leaseBreakdownsEntityId() {
            UUID id = leaseBreakdownsEntityId;
            if (id == null) {
                id = require(Entities.LEASE_BREAKDOWNS);
                leaseBreakdownsEntityId = id;
            }
            return id;
        }

        private UUID require(String code) {
            return entitiesRepository.findByCode(code)
                    .orElseThrow(() -> new IllegalStateException(
                            "Entity registry row not seeded: " + code))
                    .getId();
        }
    }
}
