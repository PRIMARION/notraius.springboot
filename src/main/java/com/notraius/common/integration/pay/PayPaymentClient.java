package com.notraius.common.integration.pay;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.notraius.config.notraius.properties.InterServiceProperties;
import com.notraius.config.notraius.properties.NotraiusProperties;
import lombok.extern.slf4j.Slf4j;
import org.primarion.module.communication.constants.Endpoint;
import org.primarion.module.communication.constants.EndpointRegistry;
import org.primarion.module.utils.utils.security.security.users.session.ServiceAuthHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.notraius.database.entities.lease.visit.Visit;

/**
 * Pay's internal money doors, as the visits module uses them.
 * <p>
 * <b>Notraius is the payment's only driver.</b> The visit payment is born through
 * pay's server-to-server route, stamped with notraius's platform: from that
 * moment pay's user-facing routes refuse it and only notraius settles it,
 * refunds it or conveys its splits — there is no admin override (PLAN 5.0).
 * <p>
 * <b>One funding, one destination, defined at birth.</b> For visits the plan
 * already knows who takes the money — the property owner — so the birth call
 * carries the split matrix with a single destination, and {@code custodial=true}
 * mints that split's escrow in the same act: wallet → escrow, held, never
 * released until the visit completes or the cancellation rule decides otherwise.
 * <p>
 * <b>The reference is the idempotency.</b> This door takes no idempotency key on
 * purpose: pay's status-aware (reference, currency) deduplication folds a
 * retried request into the funding that already exists, and a dead (refunded)
 * one no longer shadows a fresh charge — which is exactly the behaviour a
 * refunded visit that is requested again needs.
 * <p>
 * Failures are deliberately transparent: a 4xx is pay's refusal (roll the
 * request back), anything else is an unknown (never roll back — pay may have
 * taken the money). The service, not this client, decides which shape it got.
 */
@Component
@Slf4j
public class PayPaymentClient {

    private static final String FIND_CURRENCY = "pay.payment.findCurrency";
    private static final String CREATE_PAYMENT = "pay.payment.createPayment";
    private static final String GET_BY_REFERENCE = "pay.payment.getPaymentByReference";
    private static final String REFUND_PAYMENT = "pay.payment.refundPayment";
    private static final String RELEASE_SPLIT = "pay.payment.releaseSplit";
    private static final String REFUND_SPLIT = "pay.payment.refundSplit";

    private final RestClient restClient;
    private final EndpointRegistry endpoints;
    private final ServiceAuthHeaders serviceAuthHeaders;
    private final NotraiusProperties properties;

    public PayPaymentClient(NotraiusProperties properties,
                            InterServiceProperties interServiceProperties,
                            ServiceAuthHeaders serviceAuthHeaders,
                            EndpointRegistry endpoints,
                            RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.serviceAuthHeaders = serviceAuthHeaders;
        this.endpoints = endpoints;
        InterServiceProperties.Timeout timeout = interServiceProperties.getPay();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout.getConnectTimeoutMs());
        factory.setReadTimeout(timeout.getReadTimeoutMs());
        this.restClient = restClientBuilder.clone()
                .baseUrl(properties.getPayBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * The ISO code of a currency pay knows by id. The property carries pay's id;
     * the birth door speaks codes, so this is the one translation — and it fails
     * closed: an unknown id answers empty rather than inventing a code.
     */
    public Optional<String> findCurrencyCode(UUID currencyId) {
        if (currencyId == null) {
            return Optional.empty();
        }
        try {
            LookupRow row = restClient.post()
                    .uri(path(FIND_CURRENCY))
                    .headers(h -> h.setAll(serviceAuthHeaders.forTarget(properties.getPlatformTargetPay())))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new LookupRequest("currencies", "findById",
                            List.of(new LookupParam("UUID", currencyId.toString()))))
                    .retrieve()
                    .body(LookupRow.class);
            if (row == null || row.data() == null || row.data().get("code") == null) {
                return Optional.empty();
            }
            return Optional.of(row.data().get("code").toString());
        } catch (Exception e) {
            log.warn("Currency code lookup failed for currencyId={}: {}", currencyId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Fund a visit: the visitor's wallet into escrow, with the property owner
     * already named as the single split's beneficiary. The reference is the
     * visit's own id.
     */
    public PaymentRef createCustodialPayment(UUID payerId, UUID currencyId, BigDecimal amount,
                                            String activityCode, UUID referenceId,
                                            UUID beneficiaryId) {
        String currencyCode = findCurrencyCode(currencyId)
                .orElseThrow(() -> new IllegalStateException(
                        "Currency " + currencyId + " has no ISO code in the payment service"));
        BirthPaymentRequest body = new BirthPaymentRequest(
                payerId, currencyCode, amount, activityCode, referenceId, Boolean.TRUE,
                List.of(new WireSplit(beneficiaryId, amount, null, referenceId,
                        "Visit at the property")));
        PaymentRef payment = restClient.post()
                .uri(path(CREATE_PAYMENT))
                .headers(h -> h.setAll(serviceAuthHeaders.forTarget(properties.getPlatformTargetPay())))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(PaymentRef.class);
        if (payment == null || payment.id() == null) {
            throw new IllegalStateException("Payment service returned no payment for reference " + referenceId);
        }
        return payment;
    }

    /**
     * Fund the lease's deposit matrix: ONE payment whose splits are born with
     * it, each addressed by its own (entity, reference) pair (PLAN 6.0).
     * <p>
     * The lease derives the shape — one split per unit of a UNITS standard, one
     * global split per AMOUNT standard — and this door conveys it unchanged.
     * {@code custodial=true} mints one escrow per split in the same act, so the
     * tenant's wallet is debited exactly once and nothing is credited to anyone
     * until a later release or refund says so.
     */
    public PaymentRef createCustodialMatrix(UUID payerId, UUID currencyId, String activityCode,
                                            UUID referenceId, List<MatrixSplit> splits) {
        if (splits == null || splits.isEmpty()) {
            throw new IllegalStateException(
                    "A custodial matrix needs at least one split (reference " + referenceId + ")");
        }
        BigDecimal total = splits.stream()
                .map(MatrixSplit::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String currencyCode = findCurrencyCode(currencyId)
                .orElseThrow(() -> new IllegalStateException(
                        "Currency " + currencyId + " has no ISO code in the payment service"));
        BirthPaymentRequest body = new BirthPaymentRequest(
                payerId, currencyCode, total, activityCode, referenceId, Boolean.TRUE,
                splits.stream()
                        .map(split -> new WireSplit(split.beneficiaryId(), split.amount(), split.entityId(),
                                split.referenceId(), split.reason()))
                        .toList());
        PaymentRef payment = restClient.post()
                .uri(path(CREATE_PAYMENT))
                .headers(h -> h.setAll(serviceAuthHeaders.forTarget(properties.getPlatformTargetPay())))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(PaymentRef.class);
        if (payment == null || payment.id() == null) {
            throw new IllegalStateException("Payment service returned no payment for reference " + referenceId);
        }
        return payment;
    }

    /**
     * Pay's truth about a reference — the sweep's only source of truth.
     * <p>
     * A 404 is an answer: pay has no payment for this reference, so the caller
     * must be able to take the "nothing stands" branch. Letting the default
     * error handler throw turned every ordinary absence into an outage and
     * aborted the sweep before it could decide.
     */
    public Optional<PaymentRef> findByReference(UUID referenceId) {
        if (referenceId == null) {
            return Optional.empty();
        }
        PaymentRef payment = restClient.get()
                .uri(path(GET_BY_REFERENCE), referenceId)
                .headers(h -> h.setAll(serviceAuthHeaders.forTarget(properties.getPlatformTargetPay())))
                .retrieve()
                .onStatus(status -> status.value() == 404, (request, response) -> {
                    // No such reference: the empty body below is the answer.
                })
                .body(PaymentRef.class);
        return Optional.ofNullable(payment).filter(ref -> ref.id() != null);
    }

    /**
     * Convey one split's credit to the destination it was <b>born</b> with
     * (PLAN 5.3): the property owner named on the split at funding. Pay never
     * learns a destination at settle time — the birth row is the address — so an
     * owner change mid-visit cannot rewrite where the money goes.
     */
    public void releaseSplit(UUID paymentId, UUID splitId) {
        if (paymentId == null || splitId == null) {
            throw new IllegalStateException(
                    "Cannot release a split without both the payment and the split id");
        }
        restClient.post()
                .uri(path(RELEASE_SPLIT), paymentId, splitId)
                .headers(h -> h.setAll(serviceAuthHeaders.forTarget(properties.getPlatformTargetPay())))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * A <b>direct</b> payment — no escrow anywhere in its path (PLAN 5.3/5.4):
     * wallet to wallet, the penalty as a fact rather than custody. The reference
     * is the caller's own deterministic key, so a retried penalty folds into the
     * payment that already exists instead of charging twice, and the split's
     * reason says exactly what the charge is.
     */
    public PaymentRef createDirectPayment(UUID payerId, UUID currencyId, BigDecimal amount,
                                          String activityCode, UUID referenceId,
                                          UUID beneficiaryId, String reason) {
        String currencyCode = findCurrencyCode(currencyId)
                .orElseThrow(() -> new IllegalStateException(
                        "Currency " + currencyId + " has no ISO code in the payment service"));
        BirthPaymentRequest body = new BirthPaymentRequest(
                payerId, currencyCode, amount, activityCode, referenceId, Boolean.FALSE,
                List.of(new WireSplit(beneficiaryId, amount, null, referenceId, reason)));
        PaymentRef payment = restClient.post()
                .uri(path(CREATE_PAYMENT))
                .headers(h -> h.setAll(serviceAuthHeaders.forTarget(properties.getPlatformTargetPay())))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(PaymentRef.class);
        if (payment == null || payment.id() == null) {
            throw new IllegalStateException("Payment service returned no payment for reference " + referenceId);
        }
        return payment;
    }

    /**
     * Return ONE split's escrow whole to the funding wallet — the settlement's
     * refund shape (PLAN 6.7). A split's only two fates are fully released and
     * fully refunded; there is no fractional draw anywhere in the plan.
     */
    public void refundSplit(UUID paymentId, UUID splitId) {
        if (paymentId == null || splitId == null) {
            throw new IllegalStateException(
                    "Cannot refund a split without both the payment and the split id");
        }
        restClient.post()
                .uri(path(REFUND_SPLIT), paymentId, splitId)
                .headers(h -> h.setAll(serviceAuthHeaders.forTarget(properties.getPlatformTargetPay())))
                .retrieve()
                .toBodilessEntity();
    }

    /** Return the whole custody to the funding wallet: the only refund shape a visit needs. */
    public void refund(UUID paymentId) {
        restClient.post()
                .uri(path(REFUND_PAYMENT), paymentId)
                .headers(h -> h.setAll(serviceAuthHeaders.forTarget(properties.getPlatformTargetPay())))
                .retrieve()
                .toBodilessEntity();
    }

    private String path(String key) {
        return endpoints.endpoint(key)
                .map(Endpoint::getEndpoint)
                .orElseThrow(() -> new IllegalStateException("Missing endpoint for key '" + key
                        + "' in this service's endpoints.yaml"));
    }

    // ── Wire shapes (read only what this module needs) ────────────────────

    /** Pay's payment detail, narrowed to the fields a visit keeps. */
    public record PaymentRef(@JsonAlias("paymentId") UUID id,
                             BigDecimal amount,
                             String currencyCode,
                             @JsonAlias("statusCode") String statusCode,
                             String escrowStatus,
                             List<SplitInfo> splits) {

        /** The split this payment was born with (visits always fund one destination). */
        public Optional<SplitInfo> singleSplit() {
            return splits == null || splits.isEmpty() ? Optional.empty() : Optional.of(splits.get(0));
        }
    }

    public record SplitInfo(UUID splitId, UUID beneficiaryId, BigDecimal amount, String reason) {
    }

    /** One born split of a matrix: destination, figure, and its own address. */
    public record MatrixSplit(UUID beneficiaryId, BigDecimal amount, UUID entityId,
                              UUID referenceId, String reason) {
    }

    private record BirthPaymentRequest(UUID payerId, String currencyCode, BigDecimal amount,
                                       String activityCode, UUID referenceId, Boolean custodial,
                                       List<WireSplit> splits) {
    }

    private record WireSplit(UUID beneficiaryId, BigDecimal amount, UUID entityId,
                             UUID referenceId, String reason) {
    }

    private record LookupRequest(String tableName, String methodName, List<LookupParam> params) {
    }

    private record LookupParam(String type, Object value) {
    }

    private record LookupRow(UUID id, String tableName, Map<String, Object> data) {
    }
}
