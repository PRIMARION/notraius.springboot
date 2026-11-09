package com.notraius.modules.lease.agreement.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.NotraiusErrors;
import com.notraius.common.integration.PayPaymentClient;
import com.notraius.common.services.PolicyResolver;
import com.notraius.common.services.StatusResolver;
import com.notraius.config.properties.NotraiusProperties;
import com.notraius.database.entities.asset.Pricing;
import com.notraius.database.entities.asset.Property;
import com.notraius.database.entities.asset.PropertyPolicyValue;
import com.notraius.database.entities.config.Policy;
import com.notraius.database.entities.config.PolicyUnit;
import com.notraius.database.entities.lease.Lease;
import com.notraius.database.entities.lease.LeaseBreakdown;
import com.notraius.database.entities.lease.LeaseCondition;
import com.notraius.database.entities.lease.LeaseDeposit;
import com.notraius.database.entities.lease.LeaseDepositSplit;
import com.notraius.database.entities.lease.LeaseNotice;
import com.notraius.database.entities.lease.LeaseSettlement;
import com.notraius.database.entities.reference.Duration;
import com.notraius.database.entities.reference.Entities;
import com.notraius.database.entities.reference.Type;
import com.notraius.database.entities.reference.Validity;
import com.notraius.database.repositories.asset.PricingRepository;
import com.notraius.database.repositories.asset.PropertyPolicyValueRepository;
import com.notraius.database.repositories.asset.PropertyRepository;
import com.notraius.database.repositories.lease.LeaseBreakdownRepository;
import com.notraius.database.repositories.lease.LeaseConditionRepository;
import com.notraius.database.repositories.lease.LeaseDepositRepository;
import com.notraius.database.repositories.lease.LeaseDepositSplitRepository;
import com.notraius.database.repositories.lease.LeaseNoticeRepository;
import com.notraius.database.repositories.lease.LeaseRepository;
import com.notraius.database.repositories.lease.LeaseSettlementRepository;
import com.notraius.database.repositories.reference.TypeRepository;
import com.notraius.database.repositories.reference.ValidityRepository;
import com.notraius.modules.core.common.Windows;
import com.notraius.modules.core.common.constants.NotraiusActivities;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import com.notraius.modules.core.common.schedule.SweepJobs;
import com.notraius.modules.core.common.schedule.SweepPager;
import com.notraius.modules.lease.agreement.dto.request.LeaseConditionRequest;
import com.notraius.modules.lease.agreement.dto.request.LeaseCreateRequest;
import com.notraius.modules.lease.agreement.dto.request.LeaseImportRequest;
import com.notraius.modules.lease.agreement.dto.response.LeaseConditionResponse;
import com.notraius.modules.lease.agreement.dto.response.LeaseResponse;
import com.notraius.modules.lease.agreement.mapper.LeaseConditionMapper;
import com.notraius.modules.lease.agreement.mapper.LeaseMapper;
import com.notraius.modules.lease.state.service.LeaseStateService;
import lombok.extern.slf4j.Slf4j;
import org.primarion.module.access.consent.service.OwnershipGuard;
import org.primarion.module.errors.BadRequestException;
import org.primarion.module.errors.ConflictException;
import org.primarion.module.errors.ErrorCode;
import org.primarion.module.errors.ForbiddenException;
import org.primarion.module.errors.NotFoundException;
import org.primarion.module.utils.security.users.UserClient;
import org.primarion.module.utils.security.users.UserService;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The lease module's agreement sub-module (PLAN 6.0-6.2, 6.4-6.7) in one
 * service — one door per concern, one service per sub-module.
 * <p>
 * <b>Birth is funding.</b> A tenancy is minted, priced against the policy chain
 * and funded in one payment; the row is persisted before the call leaves, so the
 * reference exists whatever pay answers, and an unknown outcome stands PENDING
 * for reconciliation instead of being compensated blind.
 * <p>
 * <b>The calendar is arithmetic, not a row.</b> {@link #periods} states the four
 * laws — an anniversary never a drift, no deadline born in the past, no period
 * beyond the grant, no infinity from an open-ended validity — and {@link #sync}
 * is the only writer that turns them into installments.
 * <p>
 * <b>Every ending goes through the same money truth.</b> The cancel and the
 * staleness expiry refund split by split and hand the property back; the
 * confirmation's commit and the imported lease's verification both land on
 * {@link #activate}, because a tenancy the machine bills must be ACTIVE and
 * never billed while it is not.
 */
@Service
@Slf4j
public class LeaseAgreementService extends OwnershipGuard {

    private final PricingRepository pricingRepository;
    private final PropertyRepository propertyRepository;
    private final LeaseRepository leaseRepository;
    private final LeaseDepositRepository depositRepository;
    private final LeaseDepositSplitRepository splitRepository;
    private final ValidityRepository validityRepository;
    private final LeaseBreakdownRepository breakdownRepository;
    private final LeaseNoticeRepository noticeRepository;
    private final LeaseConditionRepository conditionRepository;
    private final LeaseSettlementRepository settlementRepository;
    private final PropertyPolicyValueRepository valueRepository;
    private final PayPaymentClient payPaymentClient;
    private final PolicyResolver policyResolver;
    private final TypeRepository typeRepository;
    private final StatusResolver statusResolver;
    private final UserService userService;
    /** The open end of a window filter, far beyond any tenancy. */
    private static final Instant FOREVER = Instant.parse("9999-12-31T23:59:59Z");

    /** Every lease status, in the order the story walks them. */
    private static final List<String> ALL_LEASE_STATUSES = List.of(
            NotraiusStatuses.LEASE_PENDING,
            NotraiusStatuses.LEASE_CONFIRMED,
            NotraiusStatuses.LEASE_ACTIVE,
            NotraiusStatuses.LEASE_DISPUTED,
            NotraiusStatuses.LEASE_COMPLETED,
            NotraiusStatuses.LEASE_CANCELLED,
            NotraiusStatuses.LEASE_EXPIRED);

    private final NotraiusProperties properties;
    private final LeaseStateService stateService;
    private final LeaseMapper leaseMapper;
    private final LeaseConditionMapper conditionMapper;
    private final AuditTrail auditTrail;
    private final TransactionTemplate transactions;
    private final SweepPager sweeps;

    private static final String SPLIT_REFERENCE_PREFIX = "LEASE-SPLIT:";
    private static final String IMPORT_SPLIT_REFERENCE_PREFIX = "LEASE-IMPORT-SPLIT:";

    private static final BigDecimal DAYS_PER_MONTH = BigDecimal.valueOf(30);
    private static final BigDecimal HOURS_PER_MONTH = BigDecimal.valueOf(720);
    private static final BigDecimal MINUTES_PER_MONTH = BigDecimal.valueOf(43_200);
    private static final BigDecimal SECONDS_PER_MONTH = BigDecimal.valueOf(2_592_000);

    /**
     * The state sub-module's notice engine reads this service back ({@code sync},
     * {@code rentOf}), so that one seam is lazy: the two sub-modules depend on
     * each other's laws without a constructor cycle.
     */
    public LeaseAgreementService(PricingRepository pricingRepository,
                                 PropertyRepository propertyRepository,
                                 LeaseRepository leaseRepository,
                                 LeaseDepositRepository depositRepository,
                                 LeaseDepositSplitRepository splitRepository,
                                 ValidityRepository validityRepository,
                                 LeaseBreakdownRepository breakdownRepository,
                                 LeaseNoticeRepository noticeRepository,
                                 LeaseConditionRepository conditionRepository,
                                 LeaseSettlementRepository settlementRepository,
                                 PropertyPolicyValueRepository valueRepository,
                                 PayPaymentClient payPaymentClient,
                                 PolicyResolver policyResolver,
                                 TypeRepository typeRepository,
                                 StatusResolver statusResolver,
                                 UserService userService,
                                 NotraiusProperties properties,
                                 @Lazy LeaseStateService stateService,
                                 LeaseMapper leaseMapper,
                                 LeaseConditionMapper conditionMapper,
                                 AuditTrail auditTrail,
                                 PlatformTransactionManager transactionManager,
                                 SweepPager sweeps) {
        this.pricingRepository = pricingRepository;
        this.propertyRepository = propertyRepository;
        this.leaseRepository = leaseRepository;
        this.depositRepository = depositRepository;
        this.splitRepository = splitRepository;
        this.validityRepository = validityRepository;
        this.breakdownRepository = breakdownRepository;
        this.noticeRepository = noticeRepository;
        this.conditionRepository = conditionRepository;
        this.settlementRepository = settlementRepository;
        this.valueRepository = valueRepository;
        this.payPaymentClient = payPaymentClient;
        this.policyResolver = policyResolver;
        this.typeRepository = typeRepository;
        this.statusResolver = statusResolver;
        this.userService = userService;
        this.properties = properties;
        this.stateService = stateService;
        this.leaseMapper = leaseMapper;
        this.conditionMapper = conditionMapper;
        this.auditTrail = auditTrail;
        this.transactions = new TransactionTemplate(transactionManager);
        this.sweeps = sweeps;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DepositStandardService
    // ═══════════════════════════════════════════════════════════════════════

    /*
     * The deposit standard of one pricing (PLAN 6.1, 6.4).
     * <p>
     * <b>One definition, resolved per state, read in its own unit, valued per
     * pricing, frozen per lease.</b> The policy chain comes from the property's
     * state ({@link PolicyResolver}); the owner's numbers come from the pricing's
     * {@link PropertyPolicyValue} rows; and the arithmetic is the operator's dynamic
     * rule, never a hardcoded ADVANCE branch:
     * <ul>
     *   <li><b>UNITS</b> — the value counts units of the pricing's own cadence, so
     *       one split is born per unit, each carrying the rent. An advance of four
     *       months is four splits, each addressed on its own so pay's analytics
     *       reconciles per month.</li>
     *   <li><b>AMOUNT</b> — one global split carries the whole figure, whatever the
     *       standard is. Its counter is the whole month-equivalents that figure
     *       covers at birth — floored once, logged, never re-divided (6.4).</li>
     * </ul>
     * <b>ADVANCE is not a special case:</b> it is a policy of type INSTALLMENT, and
     * the type/unit pair is all the machine reads.
     * <p>
     * <b>Fail closed.</b> A mandatory standard with no value refuses the birth with
     * the standard's name; a value the admin's tightened bounds left outside them
     * (FLAGGED) refuses too, because a lease funded on a number nobody accepts is
     * custody priced by a superseded figure. Nothing here guesses an unbounded
     * amount.
     */

    /**
     * Derive the whole matrix a lease born on this pricing must fund.
     * <p>
     * The result is ordered by policy code so two derivations of the same data
     * produce the same matrix — and therefore the same split sequence — which is
     * what lets a retried birth reconcile against the standing funding instead
     * of re-carving it.
     */
    @Transactional(readOnly = true)
    public DepositStandard derive(Pricing pricing) {
        UUID stateId = pricing.getProperty().getStateId();
        List<Policy> applicable = policyResolver.applicable(stateId);
        Map<UUID, PropertyPolicyValue> values = new LinkedHashMap<>();
        for (PropertyPolicyValue value : valueRepository.findByPricingId(pricing.getId())) {
            values.put(value.getPolicy().getId(), value);
        }

        List<DepositCommitment> commitments = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        for (Policy policy : applicable) {
            PropertyPolicyValue value = values.get(policy.getId());
            if (value == null) {
                if (policy.isMandatory()) {
                    throw new ConflictException(NotraiusErrors.LEASE_STANDARD_MISSING,
                            "Pricing " + pricing.getId() + " carries no value for the mandatory standard '"
                                    + policy.getCode() + "', so no lease can be funded against it");
                }
                continue;
            }
            String valueStatus = value.getStatus() != null ? value.getStatus().getCode() : null;
            if (NotraiusStatuses.POLICY_VALUE_FLAGGED.equals(valueStatus)) {
                throw new ConflictException(NotraiusErrors.LEASE_STANDARD_FLAGGED,
                        "The value " + value.getValue() + " of standard '" + policy.getCode()
                                + "' is outside its bounds and needs review before a lease uses it");
            }
            if (!NotraiusStatuses.POLICY_VALUE_ACTIVE.equals(valueStatus)) {
                // PAUSED: the standard itself is disabled, so it no longer
                // governs new values — the row stays as the property's history.
                continue;
            }

            Type depositType = typeRepository
                    .findByEntityCodeAndCode(Entities.LEASE_DEPOSITS, policy.getCode())
                    .orElseThrow(() -> new ConflictException(NotraiusErrors.LEASE_DEPOSIT_TYPE_MISSING,
                            "No lease deposit type is declared for standard '" + policy.getCode()
                                    + "'; the matrix cannot be named"));

            DepositCommitment commitment = commitment(pricing, policy, depositType, value.getValue());
            commitments.add(commitment);
            total = total.add(commitment.total());
        }

        commitments.sort((a, b) -> a.policy().getCode().compareTo(b.policy().getCode()));
        if (commitments.isEmpty()) {
            // A lease with no deposit standard at all is legal data (a hotel
            // stay with no chain), but it cannot be "funded" with zero money:
            // the payment door has nothing to take custody of.
            log.info("Pricing {} derives no deposit standard; no matrix to fund", pricing.getId());
        }
        return new DepositStandard(pricing.getId(), List.copyOf(commitments), total);
    }

    private DepositCommitment commitment(Pricing pricing, Policy policy, Type type, BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ConflictException(NotraiusErrors.LEASE_MATRIX_INVALID,
                    "Standard '" + policy.getCode() + "' values " + value
                            + ", which cannot fund custody");
        }
        if (policy.getUnit() == PolicyUnit.UNITS) {
            int units;
            try {
                units = value.stripTrailingZeros().intValueExact();
            } catch (ArithmeticException e) {
                // A split is one unit of the policy's thing: half a split does
                // not exist, and rounding would silently move someone's money.
                throw new ConflictException(NotraiusErrors.LEASE_MATRIX_INVALID,
                        "Standard '" + policy.getCode() + "' values " + value
                                + " units; a split cannot cover a fraction of a unit");
            }
            if (units <= 0) {
                throw new ConflictException(NotraiusErrors.LEASE_MATRIX_INVALID,
                        "Standard '" + policy.getCode() + "' values " + value + " units, which is no custody at all");
            }
            BigDecimal splitAmount = pricing.getAmount();
            return new DepositCommitment(policy, type, PolicyUnit.UNITS, value,
                    units, splitAmount, splitAmount.multiply(BigDecimal.valueOf(units)),
                    BigDecimal.valueOf(units), rentPerMonth(pricing));
        }

        // AMOUNT: one global split carries the whole figure; the counter is the
        // month-equivalents it covered at birth, floored once and never again.
        BigDecimal rentPerMonth = rentPerMonth(pricing);
        BigDecimal months = rentPerMonth.signum() > 0
                ? value.divide(rentPerMonth, 0, RoundingMode.DOWN)
                : BigDecimal.ZERO;
        return new DepositCommitment(policy, type, PolicyUnit.AMOUNT, value,
                1, value, value, months, rentPerMonth);
    }

    /**
     * The rent of one calendar month at this pricing, used only to translate an
     * AMOUNT hull into the counter the notices and grace arithmetic read (6.4).
     * A MONTH+1 pricing answers its own amount; anything else converts through
     * the duration chain instead of inventing a month.
     */
    BigDecimal rentPerMonth(Pricing pricing) {
        Duration duration = pricing.getDuration();
        if (duration == null || duration.getType() == null || duration.getType().getCode() == null) {
            throw new ConflictException(NotraiusErrors.LEASE_MATRIX_INVALID,
                    "Pricing " + pricing.getId() + " has no duration, so a month has no price");
        }
        int count = duration.getValue() != null ? duration.getValue() : 1;
        BigDecimal periods = BigDecimal.valueOf(count);
        String unit = duration.getType().getCode();
        BigDecimal monthsPerPeriod = switch (unit) {
            case "MONTH" -> periods;
            case "DAY" -> periods.divide(DAYS_PER_MONTH, 10, RoundingMode.HALF_UP);
            case "HOUR" -> periods.divide(HOURS_PER_MONTH, 10, RoundingMode.HALF_UP);
            case "MINUTE" -> periods.divide(MINUTES_PER_MONTH, 10, RoundingMode.HALF_UP);
            case "SECOND" -> periods.divide(SECONDS_PER_MONTH, 10, RoundingMode.HALF_UP);
            default -> throw new ConflictException(NotraiusErrors.LEASE_MATRIX_INVALID,
                    "Duration unit '" + unit + "' has no conversion to a month");
        };
        if (monthsPerPeriod.signum() <= 0) {
            throw new ConflictException(NotraiusErrors.LEASE_MATRIX_INVALID,
                    "Pricing " + pricing.getId() + " has a duration of no length");
        }
        return pricing.getAmount().divide(monthsPerPeriod, 6, RoundingMode.HALF_UP);
    }

    /** One standard's commitment: what it counters, what it carves, what it costs. */
    public record DepositCommitment(Policy policy, Type type, PolicyUnit unit, BigDecimal value,
                                    int splitCount, BigDecimal splitAmount, BigDecimal total,
                                    BigDecimal balance, BigDecimal rentPerMonth) {
    }

    /** The matrix a birth funds: one payment, every commitment's splits. */
    public record DepositStandard(UUID pricingId, List<DepositCommitment> commitments, BigDecimal total) {

        public int splitCount() {
            return commitments.stream().mapToInt(DepositCommitment::splitCount).sum();
        }

        public boolean isEmpty() {
            return commitments.isEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LeaseActivationService
    // ═══════════════════════════════════════════════════════════════════════

    /*
     * CONFIRMED → ACTIVE (PLAN 6.2): the tenancy begins and the calendar bills it.
     * <p>
     * <b>ACTIVE is what makes the validity govern.</b> The machine's later walks —
     * the reel, the stamp, the expiry, the vigile — all read the window this act
     * starts honouring, and the installments are generated in the same transaction,
     * so a lease is never ACTIVE without a calendar and never billed without being
     * live.
     * <p>
     * <b>The bypass is the boolean's blunt ruling.</b> A pricing whose
     * {@code checkable} is FALSE runs CONFIRMED → ACTIVE with no condition rows, no
     * move-in documents and no move-out negotiation — and still runs its settlement,
     * because the bypass removes bureaucracy, never money truth (6.2/6.7). An
     * imported lease takes the same road by nature: documents a tenancy that began
     * on paper cannot produce are not owed (6.6).
     * <p>
     * Activation also hands the DEPOSIT notice its first look (6.5): a lease born
     * with a one-period cushion, or an AMOUNT hull whose figure covers no whole
     * month, is critically thin from its first hour.
     */

    /** Park a CONFIRMED lease into the tenancy the machine bills. */
    @Transactional
    public LeaseResponse activate(Lease lease, UUID actor, String reason) {
        String status = lease.getStatus() != null ? lease.getStatus().getCode() : null;
        if (!NotraiusStatuses.LEASE_CONFIRMED.equals(status)) {
            throw new ConflictException(NotraiusErrors.LEASE_STATE_INVALID,
                    "Lease " + lease.getId() + " is " + status
                            + "; only a CONFIRMED lease becomes ACTIVE");
        }

        Instant now = Instant.now();
        lease.getValidity().setStatus(statusResolver.resolve(Entities.LEASES, NotraiusStatuses.LEASE_ACTIVE));
        lease.setStatus(statusResolver.resolve(Entities.LEASES, NotraiusStatuses.LEASE_ACTIVE));
        lease.setVersion(lease.getVersion() + 1);
        leaseRepository.save(lease);

        int rows = sync(lease);
        // The cushion is read from its first hour: a thin advance warns now, not
        // at the first miss (6.5).
        stateService.evaluateDeposit(lease);

        LeaseResponse response = read(lease);
        auditActivation(lease, reason + " (" + rows + " periods)", response);
        log.info("Lease {} ACTIVE by {} ({}) with {} calendar rows",
                lease.getId(), actor, reason, rows);
        return response;
    }

    private void auditActivation(Lease lease, String reason, LeaseResponse after) {
        auditTrail.record(Entities.LEASES, NotraiusActivities.Leases.ACTIVATE, lease.getId(),
                "ACTIVATE", null, after);
        log.debug("Lease {} activation audited: {}", lease.getId(), reason);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LeaseCalendar
    // ═══════════════════════════════════════════════════════════════════════

    /*
     * The tenancy's calendar, as arithmetic (PLAN 6.2's strange cases).
     * <p>
     * Four laws live here, and everywhere else they are used rather than restated:
     * <ol>
     *   <li><b>An anniversary, never a drift.</b> A period begins on the start's own
     *       day-of-month, clamped to a short month's last day — the 31st starts a
     *       February on the 28th, an April on the 30th. Every period is computed
     *       from the <em>start</em>, so a clamp never walks the rent day forward.</li>
     *   <li><b>A deadline is never born in the past.</b> A period whose start has
     *       already slipped (a mid-month signature, a cadence aligned to the first)
     *       is due <em>now</em>, the partial stretch folded into the first full
     *       deadline; overdue-on-birth would drain custody before anyone lived an
     *       hour.</li>
     *   <li><b>A period never outlives the validity.</b> The schedule is generated
     *       from the grant, so the calendar cannot bill time the tenancy was never
     *       given; the cut-away periods are the reel's arithmetic, not a debt.</li>
     *   <li><b>An open-ended validity cannot schedule an infinity.</b> It generates
     *       a bounded horizon that later ticks extend, each extension a logged act.
     *       The horizon is a property of the calendar, not of the lease's validity:
     *       a lease with no end is a lease with no end, still.</li>
     * </ol>
     * Everything is computed in UTC: a deadline is a date, and "passed" means one
     * declared clock rolled that date over, never whichever clock answered.
     */

    /** One billable period: what it grants, what it bills, and when it is late. */
    public record Period(int index, Instant start, Instant end, Instant deadline) {
    }

    /**
     * The periods from {@code from} that the grant allows.
     *
     * @param from       the tenancy's start instant
     * @param to         the validity's end, or null for the open-ended lease
     * @param now        the deployment's clock — for the never-past deadline law
     * @param horizon    how many periods an open-ended validity generates
     * @param firstIndex the index the first generated period carries
     */
    public static List<Period> periods(Instant from, Instant to, Instant now,
                                       int horizon, Duration duration, int firstIndex) {
        if (from == null) {
            throw new ConflictException(NotraiusErrors.LEASE_SCHEDULE_INVALID,
                    "A tenancy with no start has no calendar");
        }
        if (horizon <= 0) {
            throw new ConflictException(NotraiusErrors.LEASE_SCHEDULE_INVALID,
                    "The schedule horizon must generate at least one period");
        }
        String unit = duration != null && duration.getType() != null ? duration.getType().getCode() : null;
        int count = duration != null && duration.getValue() != null ? duration.getValue() : 0;
        if (unit == null || count <= 0) {
            throw new ConflictException(NotraiusErrors.LEASE_SCHEDULE_INVALID,
                    "The pricing's duration is not a positive unit, so the calendar has no cadence");
        }

        List<Period> periods = new ArrayList<>();
        for (int k = 0; ; k++) {
            Instant start = advance(from, unit, (long) count * k);
            Instant end = advance(from, unit, (long) count * (k + 1));
            if (to != null) {
                // A period that would outlive the grant is never born.
                if (!end.isBefore(to) && !end.equals(to)) {
                    break;
                }
                if (start.isAfter(to)) {
                    break;
                }
            } else if (periods.size() >= horizon) {
                break;
            }
            // Never scheduled in the past: due now, stated on the row and logged.
            Instant deadline = start.isBefore(now) ? now : start;
            periods.add(new Period(firstIndex + k, start, end, deadline));
        }
        return periods;
    }

    /** One anniversary step: month-aware where months are the unit, fixed elsewhere. */
    public static Instant advance(Instant base, String unit, long amount) {
        ZonedDateTime at = base.atZone(ZoneOffset.UTC);
        return switch (unit) {
            case "MONTH" -> at.plusMonths(amount).toInstant();
            case "YEAR" -> at.plusYears(amount).toInstant();
            case "DAY" -> at.plusDays(amount).toInstant();
            case "HOUR" -> at.plusHours(amount).toInstant();
            case "MINUTE" -> at.plusMinutes(amount).toInstant();
            case "SECOND" -> at.plusSeconds(amount).toInstant();
            default -> throw new ConflictException(NotraiusErrors.LEASE_SCHEDULE_INVALID,
                    "Duration unit '" + unit + "' has no calendar arithmetic");
        };
    }

    /** The earlier of two instants, tolerating a null end (the open-ended lease). */
    public static Instant earlier(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }

    /**
     * A span of months from an instant — the notice engine's grace and vacation
     * arithmetic, read in the bound pricing's own granularity.
     */
    public static Instant plusMonths(Instant base, int months) {
        return advance(base, "MONTH", months);
    }

    public static Instant plusDays(Instant base, int days) {
        return advance(base, "DAY", days);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LeaseCancellationService
    // ═══════════════════════════════════════════════════════════════════════

    /*
     * The lease's pre-commitment exit (PLAN 6.0/6.1).
     * <p>
     * <b>CANCELLED is the pre-commitment word only.</b> While the lease is PENDING
     * no commitment has been made and no condition has been taken, so the whole
     * matrix returns to the tenant, escrow by held escrow, in one act — and the
     * property returns to the market the same instant. A lease that was confirmed
     * or has lived has no whole matrix left to return; it ends through the
     * settlement's door (6.7), never by cancellation, because a cancellation that
     * skipped the settlement would dodge the very debts and repairs the settlement
     * exists to price.
     * <p>
     * <b>Money is refunded split by split</b> (pay's per-split refund door), never
     * as an amount: refundable value is exactly the still-held value, so a failure
     * mid-way leaves the whole act rolled back and re-runnable rather than
     * half-paying.
     * <p>
     * <b>The mid-flight refusal.</b> If the birth saga never answered, the cancel
     * asks pay for the reference's truth first. Funded → adopt and refund.
     * Definitively unfunded → nothing ever moved, so the cancel is clean. Unanswerable
     * → refuse and ask again: a refund act against custody that does not exist yet
     * would strand the money the moment it arrived.
     */

    /** The tenant's or the owner's exit from a funded intent. */
    @Transactional
    public LeaseResponse cancel(UUID leaseId, UUID callerId, String reason) {
        Lease lease = requireLease(leaseId);
        requireParty("Only the tenant or the property's owner may cancel this lease",
                NotraiusErrors.LEASE_CANCEL_FORBIDDEN, lease, callerId);
        return end(lease, NotraiusStatuses.LEASE_CANCELLED, callerId,
                NotraiusActivities.Leases.CANCEL, "CANCEL",
                reason != null && !reason.isBlank() ? reason : "cancelled before the commitment");
    }

    /**
     * The machine's own cancel: a PENDING lease whose staleness window ran out
     * (6.1's elder law, <em>silence never keeps money</em>). Same refund walk,
     * same property release, a different word on the row.
     * <p>
     * The lease is re-read here rather than handed in: the sweep carries ids
     * only, so each expiry owns a transaction — and a fully fetched graph — of
     * its own.
     */
    @Transactional
    public LeaseResponse expire(UUID leaseId) {
        Lease lease = requireLease(leaseId);
        return end(lease, NotraiusStatuses.LEASE_EXPIRED, null,
                NotraiusActivities.Leases.EXPIRE, "EXPIRE",
                "no confirmation inside the staleness window");
    }

    private LeaseResponse end(Lease lease, String terminalStatus, UUID actor,
                              String activity, String operation, String reason) {
        if (!NotraiusStatuses.LEASE_PENDING.equals(statusCodeOf(lease))) {
            throw new ConflictException(NotraiusErrors.LEASE_CANCEL_WINDOW_CLOSED,
                    "Lease " + lease.getId() + " is " + statusCodeOf(lease)
                            + "; only a PENDING lease can be cancelled");
        }

        List<LeaseDepositSplit> splits = splitRepository.findByLeaseIdOrderBySequenceAsc(lease.getId());
        if (splits.isEmpty()) {
            splits = resolveUnansweredFunding(lease);
        }
        List<LeaseDeposit> deposits = depositRepository.findByLeaseIdOrderByPolicyCodeAsc(lease.getId());

        // Snapshot BEFORE the first mutation, deposits included: the refunds and
        // the zeroed balances below are the NEW truth, and a chain whose old side
        // already states them cannot answer "what did this tenancy hold before it
        // ended?".
        LeaseResponse before = leaseMapper.toResponse(lease, deposits, splits);

        for (LeaseDepositSplit split : splits) {
            if (!NotraiusStatuses.LEASE_SPLIT_HELD.equals(statusCodeOf(split))) {
                continue;
            }
            // Whole or nothing: one split, one refund, in one call. A failure
            // here aborts the transaction, so the refunds that already landed
            // roll forward again on the retry instead of being half-counted.
            payPaymentClient.refundSplit(split.getPaymentId(), split.getSplitId());
            split.setStatus(statusResolver.resolve(Entities.LEASE_DEPOSIT_SPLITS,
                    NotraiusStatuses.LEASE_SPLIT_REFUNDED));
            if (split.getId() != null) {
                // Only an adopted split has a local row; one recovered from pay
                // during this exit is refunded without inventing ledger history.
                splitRepository.save(split);
            }
        }

        for (LeaseDeposit deposit : deposits) {
            deposit.setBalance(BigDecimal.ZERO);
            deposit.setStatus(statusResolver.resolve(Entities.LEASE_DEPOSITS,
                    NotraiusStatuses.LEASE_DEPOSIT_RETURNED));
            deposit.setVersion(deposit.getVersion() + 1);
            depositRepository.save(deposit);
        }

        lease.setStatus(statusResolver.resolve(Entities.LEASES, terminalStatus));
        lease.setVersion(lease.getVersion() + 1);
        leaseRepository.save(lease);

        releaseProperty(lease);

        LeaseResponse after = leaseMapper.toResponse(lease, deposits, splits);
        audit(activity, lease.getId(), operation, after, before);
        log.info("Lease {} {} ({}) by {}; {} held splits refunded",
                lease.getId(), terminalStatus, reason, actor, splits.size());
        return after;
    }

    /**
     * The birth saga's unknown, asked once more before anything is undone.
     * <p>
     * A 404 is pay saying "no such reference": nothing ever moved, so the exit
     * is clean. Any other failure is the unknown wearing a name, and the cancel
     * refuses rather than refunding custody that has not arrived yet.
     */
    private List<LeaseDepositSplit> resolveUnansweredFunding(Lease lease) {
        Optional<PayPaymentClient.PaymentRef> payment;
        try {
            payment = payPaymentClient.findByReference(lease.getId());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return List.of();
            }
            throw new ConflictException(NotraiusErrors.LEASE_FUNDING_UNRESOLVED,
                    "Pay answered " + e.getStatusCode().value()
                            + "; the lease stands PENDING until its funding's truth is known");
        } catch (RuntimeException e) {
            throw new ConflictException(NotraiusErrors.LEASE_FUNDING_UNRESOLVED,
                    "Pay could not be asked about lease " + lease.getId()
                            + "; the lease stands PENDING until its funding's truth is known");
        }
        if (payment.isEmpty() || payment.get().splits() == null) {
            return List.of();
        }
        // Funded but never adopted: refund pay's own splits directly. The
        // ledger stays empty because the lease is terminal — the audit entry
        // below records every split id that moved.
        PayPaymentClient.PaymentRef ref = payment.get();
        log.warn("Lease {} was funded without a ledger; refunding {} splits directly",
                lease.getId(), ref.splits().size());
        return ref.splits().stream().map(split -> {
            LeaseDepositSplit row = new LeaseDepositSplit();
            row.setPaymentId(ref.id());
            row.setSplitId(split.splitId());
            row.setAmount(split.amount());
            row.setStatus(statusResolver.resolve(Entities.LEASE_DEPOSIT_SPLITS,
                    NotraiusStatuses.LEASE_SPLIT_HELD));
            return row;
        }).toList();
    }

    /**
     * The handover: a LEASED property returns to the market at once, exactly as
     * it was before the lease (6.0 — a cancelled lease "hands the property back
     * to whatever it was before it"). Any other state is left alone: a staff
     * suspension is not the cancel's to lift.
     */
    private void releaseProperty(Lease lease) {
        Property property = lease.getPricing().getProperty();
        if (property == null || !NotraiusStatuses.PROPERTY_LEASED.equals(statusCodeOf(property))) {
            return;
        }
        property.setStatus(statusResolver.resolve(Entities.PROPERTY, NotraiusStatuses.PROPERTY_PUBLISHED));
        property.setVersion(property.getVersion() + 1);
        propertyRepository.save(property);
    }

    private String statusCodeOf(Lease lease) {
        return lease.getStatus() != null ? lease.getStatus().getCode() : null;
    }

    private String statusCodeOf(LeaseDepositSplit split) {
        return split.getStatus() != null ? split.getStatus().getCode() : null;
    }

    private String statusCodeOf(Property property) {
        return property.getStatus() != null ? property.getStatus().getCode() : null;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LeaseConditionService
    // ═══════════════════════════════════════════════════════════════════════

    /*
     * The condition stage (PLAN 6.2/6.7) — the two ends of a tenancy's evidence.
     * <p>
     * <b>Move-in: the tenant reports, the owner confirms.</b> A report is a row, not
     * a document upload, and the owner's confirmation is the act that says "yes,
     * those really were the things." The <em>last</em> confirmation parks the lease
     * ACTIVE: the conditions are a gate, and a gate opens once, not once per
     * document. A pricing whose {@code checkable} is FALSE owes no condition stage,
     * and an imported lease never owed one at all.
     * <p>
     * <b>Move-out: the landlord reports inside the move-out week, the tenant
     * validates.</b> The week is the settlement's own deadline; a report after it
     * is not accepted, because the settlement's adjudication has already begun. The
     * tenant's validation is the confirmation here, and it never re-opens the lease:
     * the settlement is the door that closes (6.7).
     * <p>
     * DISPUTED freezes all of it: a ticket means humans decide, not rows.
     */

    /** File a condition report: the tenant before move-in, the landlord after move-out. */
    @Transactional
    public LeaseConditionResponse report(UUID leaseId, UUID callerId, LeaseConditionRequest request) {
        Lease lease = requireLease(leaseId);
        String typeCode = request.getType() != null
                ? request.getType().trim().toUpperCase() : NotraiusStatuses.LEASE_CONDITION_BEFORE_MOVE_IN;
        if (!NotraiusStatuses.LEASE_CONDITION_BEFORE_MOVE_IN.equals(typeCode)
                && !NotraiusStatuses.LEASE_CONDITION_AFTER_MOVE_OUT.equals(typeCode)) {
            throw new ConflictException(NotraiusErrors.LEASE_CONDITION_INVALID,
                    "'" + request.getType() + "' is not a condition stage");
        }
        requireCheckable(lease);
        requireNotDisputed(lease);

        if (NotraiusStatuses.LEASE_CONDITION_BEFORE_MOVE_IN.equals(typeCode)) {
            requireTenant(lease, callerId, "Only the tenant reports the premises as received");
            requireStatus(lease, NotraiusStatuses.LEASE_CONFIRMED,
                    "Move-in conditions are due while the lease is CONFIRMED");
        } else {
            requireOwner(lease, callerId, "Only the landlord reports the premises as returned");
            requireStatus(lease, NotraiusStatuses.LEASE_ACTIVE,
                    "Move-out conditions are due while the tenancy is ACTIVE");
            requireMoveOutWindow(lease);
        }

        Type type = typeRepository.findByEntityCodeAndCode(Entities.LEASE_CONDITIONS, typeCode)
                .orElseThrow(() -> new IllegalStateException(
                        "No condition type '" + typeCode + "' is seeded for LEASE_CONDITIONS"));
        LeaseCondition condition = new LeaseCondition();
        condition.setLease(lease);
        condition.setType(type);
        condition.setStatus(statusResolver.resolve(Entities.LEASE_CONDITIONS,
                NotraiusStatuses.LEASE_CONDITION_PENDING));
        condition.setNotes(request.getNotes());
        condition.setVersion(1);
        condition.setCreatedBy(callerId);
        conditionRepository.save(condition);

        auditCondition(condition, NotraiusActivities.Leases.CONDITION_UPLOAD, "REPORT");
        log.info("Lease {} condition {} reported by {} as {}", leaseId, condition.getId(), callerId, typeCode);
        return conditionMapper.toResponse(condition);
    }

    /**
     * The counterpart's confirmation. The last move-in confirmation activates
     * the lease; a move-out confirmation is the tenant's validation of the
     * returned premises and moves nothing by itself.
     */
    @Transactional
    public LeaseConditionResponse confirmCondition(UUID conditionId, UUID callerId) {
        LeaseCondition condition = conditionRepository.findById(conditionId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.LEASE_CONDITION_INVALID,
                        "Lease condition not found: " + conditionId));
        Lease lease = condition.getLease();
        String typeCode = condition.getType() != null ? condition.getType().getCode() : null;
        String status = condition.getStatus() != null ? condition.getStatus().getCode() : null;
        if (!NotraiusStatuses.LEASE_CONDITION_PENDING.equals(status)) {
            throw new ConflictException(NotraiusErrors.LEASE_CONDITION_INVALID,
                    "Condition " + conditionId + " is " + status + ", not PENDING");
        }
        requireNotDisputed(lease);

        boolean moveIn = NotraiusStatuses.LEASE_CONDITION_BEFORE_MOVE_IN.equals(typeCode);
        if (moveIn) {
            requireOwner(lease, callerId, "Only the landlord confirms the move-in conditions");
            requireStatus(lease, NotraiusStatuses.LEASE_CONFIRMED,
                    "Move-in conditions are confirmed while the lease is CONFIRMED");
        } else {
            requireTenant(lease, callerId, "Only the tenant validates the move-out conditions");
            requireStatus(lease, NotraiusStatuses.LEASE_ACTIVE,
                    "Move-out conditions are validated while the tenancy is ACTIVE");
        }

        condition.setStatus(statusResolver.resolve(Entities.LEASE_CONDITIONS,
                NotraiusStatuses.LEASE_CONDITION_CONFIRMED));
        condition.setVersion(condition.getVersion() + 1);
        conditionRepository.save(condition);
        auditCondition(condition, NotraiusActivities.Leases.CONDITION_CONFIRM, "CONFIRM");
        log.info("Lease {} condition {} confirmed by {}", lease.getId(), conditionId, callerId);

        if (moveIn) {
            long outstanding = conditionRepository.countByLeaseAndTypeAndStatus(lease.getId(),
                    NotraiusStatuses.LEASE_CONDITION_BEFORE_MOVE_IN,
                    List.of(NotraiusStatuses.LEASE_CONDITION_PENDING));
            if (outstanding == 0) {
                activate(lease, callerId,
                        "all move-in conditions confirmed");
            } else {
                log.info("Lease {} still owes {} move-in condition confirmation(s)",
                        lease.getId(), outstanding);
            }
        }
        return conditionMapper.toResponse(condition);
    }

    @Transactional(readOnly = true)
    public List<LeaseConditionResponse> list(UUID leaseId, UUID callerId) {
        Lease lease = requireLease(leaseId);
        requireParty("Only a party to lease " + leaseId + " may read its conditions",
                NotraiusErrors.LEASE_PARTY_FORBIDDEN, lease, callerId);
        return conditionRepository.findByLeaseIdOrderByCreatedAtAsc(leaseId).stream()
                .map(conditionMapper::toResponse)
                .toList();
    }

    private void requireCheckable(Lease lease) {
        if (!lease.isCheckable()) {
            throw new ConflictException(NotraiusErrors.LEASE_CONDITION_INVALID,
                    "Lease " + lease.getId() + "'s pricing is not checkable, so it owes no condition stage");
        }
        if (NotraiusStatuses.LEASE_ORIGIN_IMPORT.equalsIgnoreCase(lease.getOrigin())) {
            throw new ConflictException(NotraiusErrors.LEASE_CONDITION_INVALID,
                    "An imported lease carries no platform-born condition reports");
        }
    }

    private void requireMoveOutWindow(Lease lease) {
        Optional<LeaseSettlement> settlement = settlementRepository.findByLeaseId(lease.getId());
        if (settlement.isEmpty()) {
            throw new ConflictException(NotraiusErrors.LEASE_SETTLEMENT_NOT_OPEN,
                    "Lease " + lease.getId() + " has no settlement, so no move-out report is due");
        }
        LeaseSettlement row = settlement.get();
        String status = row.getStatus() != null ? row.getStatus().getCode() : null;
        Instant weekEnds = row.getMoveOutDeadline() != null ? row.getMoveOutDeadline() : row.getDeadline();
        if (!NotraiusStatuses.LEASE_SETTLEMENT_OPEN.equals(status)
                || weekEnds == null || Instant.now().isAfter(weekEnds)) {
            throw new ConflictException(NotraiusErrors.LEASE_SETTLEMENT_NOT_OPEN,
                    "The move-out week of lease " + lease.getId() + " is closed (status "
                            + status + ", deadline " + weekEnds + ")");
        }
    }

    private void requireNotDisputed(Lease lease) {
        if (NotraiusStatuses.LEASE_DISPUTED.equalsIgnoreCase(
                lease.getStatus() != null ? lease.getStatus().getCode() : null)) {
            throw new ConflictException(NotraiusErrors.LEASE_DISPUTE_INVALID,
                    "Lease " + lease.getId() + " is DISPUTED; only a human decision moves it");
        }
    }

    private void requireStatus(Lease lease, String expected, String message) {
        String status = lease.getStatus() != null ? lease.getStatus().getCode() : null;
        if (!expected.equals(status)) {
            throw new ConflictException(NotraiusErrors.LEASE_STATE_INVALID,
                    message + " (lease " + lease.getId() + " is " + status + ")");
        }
    }

    /**
     * A party-level door: the tenant and the landlord pass directly, a seat or
     * app delegation passes through the estate's ownership guard (which re-asks
     * the domain question as the grantor), and everyone else is refused with
     * the module's own error code rather than the guard's generic one.
     */
    private void requireParty(String message, ErrorCode error, Lease lease, UUID callerId) {
        if (callerId != null
                && (callerId.equals(lease.getUserId()) || callerId.equals(lease.getOwnerId()))) {
            return;
        }
        try {
            requireOwnership(message, lease, callerId);
        } catch (ForbiddenException e) {
            throw new ForbiddenException(error, message);
        }
    }

    private void requireTenant(Lease lease, UUID callerId, String message) {
        if (callerId == null || !callerId.equals(lease.getUserId())) {
            throw new ForbiddenException(NotraiusErrors.LEASE_CONDITION_FORBIDDEN, message);
        }
    }

    private void requireOwner(Lease lease, UUID callerId, String message) {
        if (callerId == null || !callerId.equals(lease.getOwnerId())) {
            throw new ForbiddenException(NotraiusErrors.LEASE_CONDITION_FORBIDDEN, message);
        }
    }

    private void auditCondition(LeaseCondition condition, String activityCode, String operation) {
        auditTrail.record(Entities.LEASE_CONDITIONS, activityCode, condition.getId(), operation,
                null, conditionMapper.toResponse(condition));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LeaseConfirmationService
    // ═══════════════════════════════════════════════════════════════════════

    /*
     * PENDING → CONFIRMED (PLAN 6.2): the owner's commitment, and the KYC re-read
     * that guards it.
     * <p>
     * <b>The tenant signed yesterday; today's identity is what matters.</b> The
     * signing gate re-read the identity at birth, and this act reads it again: a
     * lapsed identity refuses the confirmation, and an unavailable provider refuses
     * it too — fail closed, never "carry on and check later".
     * <p>
     * <b>The checkable boolean decides the next step.</b> A pricing whose
     * {@code checkable} is TRUE parks at CONFIRMED and waits for the move-in
     * conditions; one whose boolean is FALSE activates on the spot, because there
     * is no document game to play. The bypass removes bureaucracy, never money
     * truth: the calendar and the settlement are the same machine either way.
     */

    /**
     * The owner's commitment. A checkable tenancy waits for its move-in
     * conditions; an unchecked one (or an imported one, which has no platform
     * documents by nature) becomes ACTIVE in the same act.
     */
    @Transactional
    public LeaseResponse confirm(UUID leaseId, UUID callerId) {
        Lease lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.LEASE_NOT_FOUND,
                        "Lease not found: " + leaseId));
        if (callerId == null || !callerId.equals(lease.getOwnerId())) {
            throw new ForbiddenException(NotraiusErrors.LEASE_CONFIRM_FORBIDDEN,
                    "Only the lease's beneficiary may confirm it");
        }
        String status = lease.getStatus() != null ? lease.getStatus().getCode() : null;
        if (!NotraiusStatuses.LEASE_PENDING.equals(status)) {
            throw new ConflictException(NotraiusErrors.LEASE_CONFIRM_WINDOW_CLOSED,
                    "Lease " + leaseId + " is " + status + "; only a PENDING lease is confirmed");
        }
        // The identity is re-read at commitment: yesterday's verification is not
        // today's (6.2).
        requireKycAtConfirmation(lease.getUserId());

        lease.setStatus(statusResolver.resolve(Entities.LEASES, NotraiusStatuses.LEASE_CONFIRMED));
        lease.getValidity().setStatus(statusResolver.resolve(Entities.LEASES,
                NotraiusStatuses.LEASE_CONFIRMED));
        lease.setVersion(lease.getVersion() + 1);
        leaseRepository.save(lease);
        auditConfirmation(lease, NotraiusActivities.Leases.CONFIRM, "CONFIRM", read(lease));
        log.info("Lease {} confirmed by owner {}", lease.getId(), callerId);

        if (bypassesConditions(lease)) {
            return activate(lease, callerId,
                    "pricing is not checkable, so no move-in condition stage is owed");
        }
        return read(lease);
    }

    /** TRUE when the condition stage is not owed: unchecked pricing or import. */
    public boolean bypassesConditions(Lease lease) {
        if (NotraiusStatuses.LEASE_ORIGIN_IMPORT.equalsIgnoreCase(lease.getOrigin())) {
            return true;
        }
        return !lease.isCheckable();
    }

    private void requireKycAtConfirmation(UUID userId) {
        try {
            if (!userService.isKycVerified(userId)) {
                throw new ForbiddenException(NotraiusErrors.LEASE_KYC_REQUIRED,
                        "The tenant's identity no longer verifies, so the lease cannot be confirmed");
            }
        } catch (UserClient.KycUnavailableException e) {
            throw new ConflictException(NotraiusErrors.LEASE_KYC_UNAVAILABLE,
                    "The identity provider is unavailable, so the confirmation's KYC gate cannot be passed");
        }
    }

    private void auditConfirmation(Lease lease, String activityCode, String operation, Object after) {
        auditTrail.record(Entities.LEASES, activityCode, lease.getId(), operation, null, after);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LeaseCreationService
    // ═══════════════════════════════════════════════════════════════════════

    /*
     * The lease's birth (PLAN 6.1) — the module's one funding event.
     * <p>
     * <b>One transaction of business intent.</b> The tenant names a pricing; the
     * policy chain prices the whole deposit matrix; the matrix is funded in one
     * payment whose splits are born addressed to the property owner; the property
     * flips LEASED so no second tenant can race for the same asset; and the lease
     * stands PENDING because neither party is ready yet.
     * <p>
     * <b>The saga has a written compensation law.</b> The lease row, its deposits
     * and the property's flip are persisted BEFORE the funding call leaves, so the
     * reference exists whatever the call does. A refusal (4xx) throws and rolls the
     * whole transaction back — nothing half-exists. An <em>unknown</em> (a severed
     * connection, an outage) is never compensated blind: the row stands PENDING
     * with its intent persisted and the sweep, or the retry, asks pay for the
     * reference's truth. A retried birth finds its own funding by that reference and
     * adopts it, reconciling the standing matrix against the standard it is about to
     * serve — a mismatch refuses loudly instead of writing custody priced by a
     * superseded figure.
     * <p>
     * <b>Nothing is funded against a hole.</b> A mandatory standard with no value,
     * a value the admin's tightened bounds flagged, a standard with no deposit type,
     * a fractional unit a split cannot carve — each refuses with its name.
     */

    // ═══════════════════════════════════════════════════════════════════════
    // Birth
    // ═══════════════════════════════════════════════════════════════════════

    @Transactional
    public LeaseResponse create(LeaseCreateRequest request, UUID tenantId) {
        Pricing pricing = requireAnsweringPricing(request.getPricingId());
        Property property = pricing.getProperty();
        if (tenantId.equals(property.getOwnerId())) {
            throw new BadRequestException(NotraiusErrors.LEASE_OWN_PROPERTY,
                    "Property " + property.getId() + " is already yours; it cannot be leased from yourself");
        }
        requireKyc(tenantId);

        Instant from = request.getFromDate() != null ? request.getFromDate() : Instant.now();
        Instant to = request.getToDate();
        if (to != null && !to.isAfter(from)) {
            throw new BadRequestException(NotraiusErrors.LEASE_WINDOW_INVALID,
                    "Window [" + from + ", " + to + ") does not have a positive length");
        }

        DepositStandard standard = derive(pricing);

        // The retry's law: the same tenant's PENDING lease on this pricing is
        // adopted, never duplicated — its id is the funding reference. It is
        // asked BEFORE the availability gate, because the first attempt already
        // flipped the property LEASED: a retry must find its own row, not be
        // refused by the flip its own birth caused.
        Optional<Lease> reusable = leaseRepository
                .findReusable(tenantId, pricing.getId(), List.of(NotraiusStatuses.LEASE_PENDING))
                .stream().findFirst();
        if (reusable.isPresent()) {
            return resume(reusable.get(), standard, tenantId);
        }

        requireAvailable(property);
        if (property.getCurrencyId() == null) {
            throw new ConflictException(NotraiusErrors.LEASE_CURRENCY_UNRESOLVED,
                    "Property " + property.getId() + " has no currency, so its deposits cannot be priced");
        }

        if (leaseRepository.countLiveForProperty(property.getId(), NotraiusStatuses.LEASE_LIVE_STATUSES) > 0) {
            throw new ConflictException(NotraiusErrors.LEASE_CREATE_RACE,
                    "Property " + property.getId() + " already carries a live lease");
        }

        Validity validity = statusResolver.openValidity(Entities.LEASES, NotraiusStatuses.LEASE_PENDING);
        validity.setFromDate(from);
        validity.setToDate(to);
        validityRepository.save(validity);

        Lease lease = new Lease();
        lease.setUserId(tenantId);
        lease.setOwnerId(property.getOwnerId());
        lease.setPricing(pricing);
        lease.setTermsId(request.getTermsId());
        lease.setValidity(validity);
        lease.setStatus(statusResolver.resolve(Entities.LEASES, NotraiusStatuses.LEASE_PENDING));
        // The rent is frozen here, once, in the money the birth is about to
        // move: the calendar bills this figure for the whole tenancy (6.2).
        lease.setRentAmount(pricing.getAmount());
        // The condition term is frozen with it (V38): the pricing's flag stays
        // the offer, this is what the tenant actually owes.
        lease.setCheckable(pricing.isCheckable());
        // And the cadence (V39): the calendar bills on the term signed, not on
        // whatever the pricing points at when the next schedule is generated.
        lease.setDuration(pricing.getDuration());
        lease.setOrigin(NotraiusStatuses.LEASE_ORIGIN_NATIVE);
        lease.setVersion(1);
        lease.setCreatedBy(tenantId);
        // Persisted BEFORE the funding call leaves: the reference (this id) must
        // exist whether or not the call ever answers (6.1's funding law).
        leaseRepository.save(lease);

        List<LeaseDeposit> deposits = saveDeposits(lease, standard);

        // The property flips the moment the lease exists: no second tenant can
        // race for the same asset, and no viewing is offered while it is
        // someone's home (6.0).
        property.setStatus(statusResolver.resolve(Entities.PROPERTY, NotraiusStatuses.PROPERTY_LEASED));
        property.setVersion(property.getVersion() + 1);
        propertyRepository.save(property);

        if (standard.isEmpty()) {
            // A pricing with no policy chain (the hotel case) has nothing to
            // fund at birth: nothing is outstanding, so the row is resolved.
            LeaseResponse response = read(lease, deposits);
            response.setFundingResolved(true);
            audit(NotraiusActivities.Leases.CREATE, lease.getId(), "CREATE", response, null);
            log.info("Lease {} born without a deposit matrix (pricing {} has no policy chain)",
                    lease.getId(), pricing.getId());
            return response;
        }

        return fund(lease, property, standard, tenantId, false);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Funding
    // ═══════════════════════════════════════════════════════════════════════

    private LeaseResponse fund(Lease lease, Property property,
                               DepositStandard standard,
                               UUID tenantId, boolean adopted) {
        List<PayPaymentClient.MatrixSplit> wire = matrix(lease, standard);
        PayPaymentClient.PaymentRef payment;
        try {
            payment = payPaymentClient.createCustodialMatrix(
                    tenantId, property.getCurrencyId(), NotraiusActivities.Leases.CREATE,
                    lease.getId(), wire);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                // A refusal: nobody paid, so nothing stands. Throwing rolls the
                // lease row, its deposits and the property's flip back together.
                throw new ConflictException(NotraiusErrors.LEASE_PAYMENT_REFUSED,
                        "The deposit matrix was refused (" + e.getStatusCode().value()
                                + "), so no lease was created");
            }
            return leavePending(lease, "pay answered " + e.getStatusCode());
        } catch (RuntimeException e) {
            // The unknown: the call may have taken the money. Never compensate
            // blind — the row stands PENDING for the reference's truth.
            return leavePending(lease, e.getMessage());
        }

        if (!adopt(lease, standard, payment, wire)) {
            return leavePending(lease, "pay returned a split count that does not match the matrix");
        }
        LeaseResponse response = read(lease);
        audit(adopted ? NotraiusActivities.Leases.CREATE_ADOPT : NotraiusActivities.Leases.CREATE,
                lease.getId(), adopted ? "ADOPT" : "CREATE", response, null);
        log.info("Lease {} {} for tenant={} property={} amount={} splits={} payment={}",
                lease.getId(), adopted ? "adopted" : "funded", tenantId, property.getId(),
                payment.amount(), wire.size(), payment.id());
        return response;
    }

    /**
     * The retried birth: find the funding the first attempt left behind.
     * <p>
     * An answer of <em>funded</em> is adopted after reconciling the standing
     * matrix against the standard this birth is about to serve; an answer of
     * <em>nothing stands</em> funds the intent now; an unanswerable call leaves
     * the lease PENDING for the next ask — never a compensation guessed in the
     * dark.
     */
    private LeaseResponse resume(Lease lease,
                                 DepositStandard standard,
                                 UUID tenantId) {
        List<LeaseDeposit> deposits = depositRepository.findByLeaseIdOrderByPolicyCodeAsc(lease.getId());
        List<LeaseDepositSplit> splits = splitRepository.findByLeaseIdOrderBySequenceAsc(lease.getId());
        if (!splits.isEmpty()) {
            LeaseResponse response = read(lease, deposits);
            audit(NotraiusActivities.Leases.CREATE_ADOPT, lease.getId(), "ADOPT", response, null);
            return response;
        }

        PayPaymentClient.PaymentRef payment;
        try {
            Optional<PayPaymentClient.PaymentRef> found = payPaymentClient.findByReference(lease.getId());
            if (found.isEmpty()) {
                return fund(lease, lease.getPricing().getProperty(), standard, tenantId, true);
            }
            payment = found.get();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return fund(lease, lease.getPricing().getProperty(), standard, tenantId, true);
            }
            return leavePending(lease, "pay answered " + e.getStatusCode());
        } catch (RuntimeException e) {
            return leavePending(lease, e.getMessage());
        }

        if (!matrixMatches(payment, standard)) {
            // Money stands; this side cannot explain it. Refuse loudly for a
            // human to reconcile rather than carry custody priced by a figure
            // the policy chain no longer produces (6.1's adoption law).
            throw new ConflictException(NotraiusErrors.LEASE_MATRIX_INVALID,
                    "The standing funding of lease " + lease.getId()
                            + " does not match the deposit standard this birth would serve");
        }
        adopt(lease, standard, payment, matrix(lease, standard));
        LeaseResponse response = read(lease, deposits);
        audit(NotraiusActivities.Leases.CREATE_ADOPT, lease.getId(), "ADOPT", response, null);
        return response;
    }

    /**
     * Write pay's born splits into this side's ledger — the FIFO and settlement
     * walks read it instead of re-reading the whole payment on every tick.
     * <p>
     * The response carries no reference per split, so the zip is by birth order:
     * the wire list and the echoed splits are the same matrix in the same order.
     * A count mismatch is not an exception here — the money already moved, and
     * throwing would roll the lease back around it; the caller leaves the row
     * PENDING and the reconcile (by reference) resolves it later.
     */
    private boolean adopt(Lease lease, DepositStandard standard,
                          PayPaymentClient.PaymentRef payment,
                          List<PayPaymentClient.MatrixSplit> wire) {
        List<PayPaymentClient.SplitInfo> born = payment.splits() != null ? payment.splits() : List.of();
        if (born.size() != wire.size()) {
            log.warn("Lease {} funding {} returned {} splits for a {} split matrix",
                    lease.getId(), payment.id(), born.size(), wire.size());
            return false;
        }

        Map<String, LeaseDeposit> deposits = new HashMap<>();
        for (LeaseDeposit deposit : depositRepository.findByLeaseIdOrderByPolicyCodeAsc(lease.getId())) {
            deposits.put(deposit.getPolicy().getCode(), deposit);
        }

        int index = 0;
        int sequence = 1;
        for (DepositCommitment commitment : standard.commitments()) {
            LeaseDeposit deposit = deposits.get(commitment.policy().getCode());
            for (int unit = 1; unit <= commitment.splitCount(); unit++) {
                PayPaymentClient.SplitInfo split = born.get(index);
                PayPaymentClient.MatrixSplit addressed = wire.get(index);
                LeaseDepositSplit row = new LeaseDepositSplit();
                row.setLease(lease);
                row.setDeposit(deposit);
                row.setPaymentId(payment.id());
                row.setSplitId(split.splitId());
                row.setReferenceId(addressed.referenceId());
                row.setAmount(split.amount() != null ? split.amount() : addressed.amount());
                row.setSequence(sequence);
                row.setStatus(statusResolver.resolve(Entities.LEASE_DEPOSIT_SPLITS,
                        NotraiusStatuses.LEASE_SPLIT_HELD));
                splitRepository.save(row);
                index++;
                sequence++;
            }
        }
        return true;
    }

    /** The matrix as pay's birth door takes it: one split per unit, addressed. */
    private List<PayPaymentClient.MatrixSplit> matrix(Lease lease,
                                                      DepositStandard standard) {
        UUID entityId = statusResolver.resolveEntity(Entities.LEASES).getId();
        List<PayPaymentClient.MatrixSplit> splits = new ArrayList<>();
        for (DepositCommitment commitment : standard.commitments()) {
            for (int unit = 1; unit <= commitment.splitCount(); unit++) {
                UUID reference = splitReference(lease.getId(), commitment.policy().getCode(), unit);
                String reason = commitment.policy().getCode() + " " + unit + "/" + commitment.splitCount()
                        + " — lease " + lease.getId();
                splits.add(new PayPaymentClient.MatrixSplit(lease.getOwnerId(), commitment.splitAmount(),
                        entityId, reference, reason));
            }
        }
        return splits;
    }

    /**
     * A split's own address, derived deterministically: a retried birth that
     * must re-send the matrix names the same months, so pay's analytics never
     * sees the same escrow described twice.
     */
    static UUID splitReference(UUID leaseId, String policyCode, int unit) {
        return UUID.nameUUIDFromBytes(
                (SPLIT_REFERENCE_PREFIX + leaseId + ":" + policyCode + ":" + unit)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private boolean matrixMatches(PayPaymentClient.PaymentRef payment,
                                  DepositStandard standard) {
        BigDecimal amount = payment.amount() != null ? payment.amount() : BigDecimal.ZERO;
        int splits = payment.splits() != null ? payment.splits().size() : 0;
        return amount.compareTo(standard.total()) == 0 && splits == standard.splitCount();
    }

    /**
     * The unknown's shape: the row stands PENDING, its intent persisted, and the
     * sweep or the retry asks the reference's truth again. Never an exception —
     * the tenant must be able to see the row that may already hold their money.
     */
    private LeaseResponse leavePending(Lease lease, String reason) {
        log.warn("Lease {} funding outcome unknown ({}); left PENDING for reconciliation",
                lease.getId(), reason);
        LeaseResponse response = read(lease);
        audit(NotraiusActivities.Leases.FUND_UNKNOWN, lease.getId(), "UNKNOWN", response, null);
        return response;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Read
    // ═══════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public LeaseResponse getLease(UUID leaseId, UUID callerId) {
        Lease lease = requireLease(leaseId);
        requireOwnership("Only a party to the lease may read it", lease, callerId);
        return withActions(lease, callerId);
    }

    /**
     * The tenant's own page — every status unless narrowed, newest first unless
     * ordered by the term. The filters exist for the same reason the visit's do:
     * a tenant who has moved twice does not want to page past two dead tenancies
     * to find the one that still holds their deposit, and "what is still
     * running" is a question the server can answer with one indexable predicate.
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<LeaseResponse> listMine(UUID tenantId, List<String> statuses,
                                                                       Instant from, Instant to,
                                                                       org.springframework.data.domain.Pageable pageable) {
        requireOrderedWindow(from, to);
        return leaseRepository.searchAsTenant(tenantId, checkedLeaseStatuses(statuses),
                        orEpoch(from), orForever(to), pageable)
                .map(lease -> withActions(lease, tenantId));
    }

    /** The beneficiary's page, with the same filters and its own next acts. */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<LeaseResponse> listOwned(UUID ownerId, List<String> statuses,
                                                                        Instant from, Instant to,
                                                                        org.springframework.data.domain.Pageable pageable) {
        requireOrderedWindow(from, to);
        return leaseRepository.searchAsOwner(ownerId, checkedLeaseStatuses(statuses),
                        orEpoch(from), orForever(to), pageable)
                .map(lease -> withActions(lease, ownerId));
    }

    /**
     * The page's order, by name, refused rather than guessed — the visit pages'
     * law. {@code recent} is the historical order (newest tenancy first);
     * {@code window} is the tenant's order (the term that starts soonest first).
     */
    public static org.springframework.data.domain.Sort sortOf(String sort) {
        String name = sort == null || sort.isBlank()
                ? "recent" : sort.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (name) {
            case "recent" -> org.springframework.data.domain.Sort
                    .by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")
                    .and(org.springframework.data.domain.Sort.by("id"));
            case "window" -> org.springframework.data.domain.Sort
                    .by(org.springframework.data.domain.Sort.Direction.ASC, "validity.fromDate")
                    .and(org.springframework.data.domain.Sort.by("id"));
            default -> throw new BadRequestException(NotraiusErrors.LEASE_FILTER_INVALID,
                    "sort must be 'recent' or 'window', not '" + sort + "'");
        };
    }

    /** The row plus this caller's next acts; one field, no extra read. */
    private LeaseResponse withActions(Lease lease, UUID callerId) {
        LeaseResponse response = read(lease);
        response.setNextActions(LeaseNextActions.of(lease, callerId, response.isFundingResolved()));
        return response;
    }

    /** "No lower bound" as an instant — the query has no nullable date bind. */
    private static Instant orEpoch(Instant from) {
        return from != null ? from : Instant.EPOCH;
    }

    /** "No upper bound" as an instant: no tenancy outlives the year 9999. */
    private static Instant orForever(Instant to) {
        return to != null ? to : FOREVER;
    }

    /** An inverted window is a client fault, not an empty page. */
    private static void requireOrderedWindow(Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException(NotraiusErrors.LEASE_FILTER_INVALID,
                    "from must not be after to");
        }
    }

    /**
     * The status filter as the query wants it: a non-empty set of real codes.
     * "No filter" becomes every status so the SQL keeps one indexable {@code IN};
     * a code that is not a lease status is refused by name.
     */
    private static List<String> checkedLeaseStatuses(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return ALL_LEASE_STATUSES;
        }
        java.util.Set<String> resolved = new java.util.LinkedHashSet<>();
        for (String status : statuses) {
            String code = status == null ? "" : status.trim().toUpperCase(java.util.Locale.ROOT);
            if (!ALL_LEASE_STATUSES.contains(code)) {
                throw new BadRequestException(NotraiusErrors.LEASE_FILTER_INVALID,
                        "'" + status + "' is not a lease status");
            }
            resolved.add(code);
        }
        return List.copyOf(resolved);
    }

    private LeaseResponse read(Lease lease) {
        return read(lease, depositRepository.findByLeaseIdOrderByPolicyCodeAsc(lease.getId()));
    }

    private LeaseResponse read(Lease lease, List<LeaseDeposit> deposits) {
        return leaseMapper.toResponse(lease, deposits,
                splitRepository.findByLeaseIdOrderBySequenceAsc(lease.getId()));
    }

    /**
     * Parties to a lease are the tenant and the beneficiary its birth named.
     * The landlord-side counterpart of an installment is a recipient, not a
     * party, and is never inferred from these two ids (6.9).
     */
    @Override
    public boolean canAccess(Object... args) {
        if (args.length < 2) {
            return false;
        }
        if (!(args[0] instanceof Lease lease) || !(args[1] instanceof UUID callerId)) {
            return false;
        }
        if (callerId == null) {
            return false;
        }
        return callerId.equals(lease.getUserId()) || callerId.equals(lease.getOwnerId());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private List<LeaseDeposit> saveDeposits(Lease lease,
                                            DepositStandard standard) {
        List<LeaseDeposit> deposits = new ArrayList<>();
        for (DepositCommitment commitment : standard.commitments()) {
            LeaseDeposit deposit = new LeaseDeposit();
            deposit.setLease(lease);
            deposit.setPolicy(commitment.policy());
            deposit.setType(commitment.type());
            deposit.setBalance(commitment.balance());
            // The birth's own standard, kept beside the counter so the notice
            // engine never reads today's policy to judge yesterday's lease.
            deposit.setStandardBalance(commitment.balance());
            deposit.setStatus(statusResolver.resolve(Entities.LEASE_DEPOSITS,
                    NotraiusStatuses.LEASE_DEPOSIT_HELD));
            deposit.setVersion(1);
            deposits.add(depositRepository.save(deposit));
        }
        return deposits;
    }

    private Lease requireLease(UUID leaseId) {
        return leaseRepository.findById(leaseId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.LEASE_NOT_FOUND,
                        "Lease not found: " + leaseId));
    }

    /**
     * The pricing must be LIVE <em>and</em> answering now: a window that has
     * ended is history the lease may read, never a future it starts from
     * (6.1's refusal, Part 4.2's law).
     */
    private Pricing requireAnsweringPricing(UUID pricingId) {
        Pricing pricing = pricingRepository.findById(pricingId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.PRICING_NOT_FOUND,
                        "Pricing not found: " + pricingId));
        String status = pricing.getStatus() != null ? pricing.getStatus().getCode() : null;
        if (!NotraiusStatuses.PRICING_LIVE.equals(status)) {
            throw new ConflictException(NotraiusErrors.LEASE_PRICING_NOT_LIVE,
                    "Pricing " + pricingId + " is " + status);
        }
        Validity window = pricing.getValidity();
        if (window == null
                || !Windows.contains(window.getFromDate(), window.getToDate(), Instant.now())) {
            throw new ConflictException(NotraiusErrors.LEASE_PRICING_EXPIRED,
                    "Pricing " + pricingId + "'s window does not answer now");
        }
        return pricing;
    }

    private void requireAvailable(Property property) {
        String status = property.getStatus() != null ? property.getStatus().getCode() : null;
        if (!NotraiusStatuses.PROPERTY_PUBLISHED.equals(status)) {
            throw new ConflictException(NotraiusErrors.LEASE_PROPERTY_NOT_AVAILABLE,
                    "Property " + property.getId() + " is " + status
                            + " and can only receive visits and leases while PUBLISHED");
        }
    }

    /** KYC gates the signing: an identity that lapses is re-read at confirmation too (6.1). */
    private void requireKyc(UUID callerId) {
        try {
            if (!userService.isKycVerified(callerId)) {
                throw new ForbiddenException(NotraiusErrors.LEASE_KYC_REQUIRED,
                        "A verified identity is required to sign a lease");
            }
        } catch (UserClient.KycUnavailableException e) {
            throw new ConflictException(NotraiusErrors.LEASE_KYC_UNAVAILABLE,
                    "The identity provider is unavailable, so the lease's KYC gate cannot be passed");
        }
    }

    private void audit(String activityCode, UUID referenceId, String operation, Object after, Object before) {
        auditTrail.record(Entities.LEASES, activityCode, referenceId, operation, before, after);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LeaseExpiryService
    // ═══════════════════════════════════════════════════════════════════════

    /*
     * The clock half of 6.1: silence never keeps money.
     * <p>
     * A lease is born PENDING, holding the tenant's whole deposit matrix in escrow
     * while the owner's confirmation is owed. If that confirmation never comes
     * inside the staleness window, the intent is over: every still-held split
     * returns to the tenant, the ledger is zeroed, and the property goes back on the
     * market — the same walk the tenant's own cancel takes, with EXPIRED on the row.
     * <p>
     * <b>One row, one transaction.</b> The sweep carries ids and asks
     * {@link LeaseCancellationService#expire(UUID)} per id, so a row whose funding
     * truth cannot be answered rolls back alone and stands PENDING for the next
     * tick, instead of taking the whole sweep — and the refunds already made in it —
     * down with it. The number rides the environment
     * ({@code app.notraius.lease-staleness-window-hours}); the rule is fixed.
     * <p>
     * <b>At scale the worklist is read in pages, not whole.</b> Ten million
     * pending leases is not a list this JVM can hold; it is a keyset of
     * {@code (createdAt, id)} read {@code sweep-page-size} keys at a time, each
     * expiry owning its transaction, and a pass that hits
     * {@code sweep-row-budget} resuming from the ledger's cursor on the next
     * tick instead of starting over ({@link SweepPager}).
     */

    /**
     * One budgeted pass. Returns how many leases it expired, so a caller — a
     * test, an ops button — can see that it did real work.
     * <p>
     * Deliberately not {@code @Transactional}: the pager opens one transaction
     * per row, and a pass-wide transaction would collapse them into one —
     * exactly the failure mode the per-row boundary exists to prevent.
     */
    public int sweep() {
        Instant now = Instant.now();
        Instant fence = now.minus(properties.getLeaseStalenessWindowHours(), ChronoUnit.HOURS);
        Instant lookback = now.minus(properties.getLeaseSweepLookbackHours(), ChronoUnit.HOURS);

        return sweeps.run(SweepJobs.LEASE_EXPIRY,
                (afterKey, afterId, limit) -> leaseRepository.findStalePage(
                        NotraiusStatuses.LEASE_PENDING, fence, lookback, afterKey, afterId,
                        PageRequest.of(0, limit)),
                leaseId -> {
                    expire(leaseId);
                    return 1;
                });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LeaseImportService
    // ═══════════════════════════════════════════════════════════════════════

    /*
     * The imported tenancy (PLAN 6.6) — Case one's debts and Case two's holdings.
     * <p>
     * <b>Case one: the landlord signs the backlog.</b> Each declared month becomes an
     * installment row <em>born OVERDUE at its historical date</em>: the debts are the
     * record of the past, so the never-past-deadline law governs the forward
     * schedule only. A period the tenancy's window never contained refuses loudly
     * rather than materializing an impossible debt.
     * <p>
     * <b>Case two: the landlord funds the declared holdings.</b> Custody at import
     * time is custody, whatever the notepad said: the landlord pays and the escrows
     * stand for the tenant's deposit truth exactly like a platform-born lease. The
     * declarations are frozen at import — the money truth from then on — and each
     * still-HELD escrow is refunded whole at settlement.
     * <p>
     * <b>The tenant verifies wholesale.</b> ACCEPT starts the strictest schedule: a
     * RECONVERSION clock of ceil(unpaid × the configured multiplier) months becomes
     * the validity of record (never longer than the paper tenancy's own end), and one
     * skipped period escalates straight to VACATION. A backlog cleared inside the
     * window dissolves the borrowed clock and the tenancy continues open-ended, the
     * DEPOSIT demand taking over. REFUSE cancels the lease and returns the import's
     * funding whole to the wallet it came from.
     * <p>
     * <b>Imports carry no move-in conditions by nature</b>: the platform-born
     * documents cannot exist for a tenancy that started on paper, so the imported
     * lease runs CONFIRMED → ACTIVE directly. The settlement remains universal.
     */

    // ═══════════════════════════════════════════════════════════════════════
    // Creation
    // ═══════════════════════════════════════════════════════════════════════

    /** The landlord declares the paper tenancy: debts, holdings, or both. */
    @Transactional
    public LeaseResponse create(LeaseImportRequest request, UUID callerId) {
        if ((request.getDebts() == null || request.getDebts().isEmpty())
                && (request.getHoldings() == null || request.getHoldings().isEmpty())) {
            throw new BadRequestException(NotraiusErrors.LEASE_IMPORT_INVALID,
                    "An import must declare its debts, its holdings, or both");
        }
        if (request.getTenantId() == null || request.getTenantId().equals(callerId)) {
            throw new BadRequestException(NotraiusErrors.LEASE_IMPORT_INVALID,
                    "An imported lease must name a tenant other than its landlord");
        }

        Pricing pricing = pricingRepository.findById(request.getPricingId())
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.PRICING_NOT_FOUND,
                        "Pricing not found: " + request.getPricingId()));
        Property property = pricing.getProperty();
        if (property == null || !callerId.equals(property.getOwnerId())) {
            throw new ForbiddenException(NotraiusErrors.LEASE_CONFIRM_FORBIDDEN,
                    "Only the property's owner may import a lease on it");
        }
        if (!NotraiusStatuses.PROPERTY_PUBLISHED.equals(
                property.getStatus() != null ? property.getStatus().getCode() : null)) {
            throw new ConflictException(NotraiusErrors.LEASE_PROPERTY_NOT_AVAILABLE,
                    "Property " + property.getId() + " is "
                            + (property.getStatus() != null ? property.getStatus().getCode() : null)
                            + " and can only receive an import while PUBLISHED");
        }
        if (property.getCurrencyId() == null) {
            throw new ConflictException(NotraiusErrors.LEASE_CURRENCY_UNRESOLVED,
                    "Property " + property.getId() + " has no currency, so its import cannot be funded");
        }
        if (leaseRepository.countLiveForProperty(property.getId(),
                NotraiusStatuses.LEASE_LIVE_STATUSES) > 0) {
            throw new ConflictException(NotraiusErrors.LEASE_CREATE_RACE,
                    "Property " + property.getId() + " already carries a live lease");
        }

        Instant from = request.getFromDate() != null ? request.getFromDate() : Instant.now();
        Instant to = request.getToDate();
        if (to != null && !to.isAfter(from)) {
            throw new BadRequestException(NotraiusErrors.LEASE_WINDOW_INVALID,
                    "Window [" + from + ", " + to + ") does not have a positive length");
        }
        validateDebts(request.getDebts(), from, to);
        Map<String, LeaseImportRequest.HoldingLine> holdings = indexHoldings(request.getHoldings());

        Validity validity = statusResolver.openValidity(Entities.LEASES, NotraiusStatuses.LEASE_PENDING);
        validity.setFromDate(from);
        validity.setToDate(to);
        validityRepository.save(validity);

        Lease lease = new Lease();
        lease.setUserId(request.getTenantId());
        lease.setOwnerId(callerId);
        lease.setPricing(pricing);
        lease.setTermsId(request.getTermsId());
        lease.setValidity(validity);
        lease.setStatus(statusResolver.resolve(Entities.LEASES, NotraiusStatuses.LEASE_PENDING));
        lease.setOrigin(NotraiusStatuses.LEASE_ORIGIN_IMPORT);
        lease.setRentAmount(pricing.getAmount());
        lease.setCheckable(pricing.isCheckable());
        lease.setDuration(pricing.getDuration());
        lease.setVersion(1);
        lease.setCreatedBy(callerId);
        leaseRepository.save(lease);

        List<LeaseDeposit> deposits = writeDeposits(lease, pricing, holdings);
        writeDebts(lease, property, request.getDebts());

        property.setStatus(statusResolver.resolve(Entities.PROPERTY, NotraiusStatuses.PROPERTY_LEASED));
        property.setVersion(property.getVersion() + 1);
        propertyRepository.save(property);

        if (holdings.isEmpty()) {
            // No custody to fund: the declared debts are the whole import.
            LeaseResponse response = read(lease);
            response.setFundingResolved(true);
            auditImport(NotraiusActivities.Leases.IMPORT_CREATE, lease, "CREATE");
            log.info("Imported lease {} created for tenant {} with {} debt row(s)",
                    lease.getId(), request.getTenantId(), request.getDebts().size());
            return response;
        }

        LeaseResponse response = fundHoldings(lease, property, pricing, holdings, callerId);
        auditImport(NotraiusActivities.Leases.IMPORT_CREATE, lease, "CREATE");
        log.info("Imported lease {} created for tenant {} ({} debts, {} holdings)",
                lease.getId(), request.getTenantId(), request.getDebts().size(), holdings.size());
        return response;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // The tenant's wholesale verification
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * ACCEPT: the import's truth is verified, the reconversion clock starts if a
     * backlog stands, and the lease activates directly (no conditions by origin).
     */
    @Transactional
    public LeaseResponse accept(UUID leaseId, UUID callerId) {
        Lease lease = requireImport(leaseId, callerId);
        requireFundingResolved(lease);
        Instant now = Instant.now();
        List<LeaseBreakdown> backlog = tenantOwed(lease);

        Instant priorEnd = lease.getValidity().getToDate();
        lease.getValidity().setFromDate(now);
        if (!backlog.isEmpty()) {
            long months = BigDecimal.valueOf(backlog.size())
                    .multiply(properties.getLeaseReconversionMultiplier())
                    .setScale(0, RoundingMode.CEILING).longValueExact();
            Instant clockEnd = plusMonths(now, (int) Math.max(1, months));
            Instant end = priorEnd == null || clockEnd.isBefore(priorEnd) ? clockEnd : priorEnd;
            if (!end.isAfter(now)) {
                throw new ConflictException(NotraiusErrors.LEASE_IMPORT_PERIOD_INVALID,
                        "The paper tenancy's own end leaves no reconversion window");
            }
            stateService.issueReconversion(lease, end,
                    "Imported backlog of " + backlog.size()
                            + " installment(s) cleared at one per two-month stretch");
            lease.getValidity().setToDate(end);
        }
        lease.getValidity().setStatus(statusResolver.resolve(Entities.LEASES,
                NotraiusStatuses.LEASE_CONFIRMED));
        lease.setStatus(statusResolver.resolve(Entities.LEASES, NotraiusStatuses.LEASE_CONFIRMED));
        lease.setVersion(lease.getVersion() + 1);
        leaseRepository.save(lease);

        auditImport(NotraiusActivities.Leases.IMPORT_ACCEPT, lease, "ACCEPT");
        log.info("Imported lease {} accepted by tenant {} ({} backlog row(s), validity {} → {})",
                leaseId, callerId, backlog.size(), lease.getValidity().getFromDate(),
                lease.getValidity().getToDate());
        return activate(lease, callerId, "imported lease verified wholesale");
    }

    /** REFUSE: the lease dies whole and the import's funding returns to its wallet. */
    @Transactional
    public LeaseResponse refuse(UUID leaseId, UUID callerId) {
        requireImport(leaseId, callerId);
        auditImport(NotraiusActivities.Leases.IMPORT_REFUSE, leaseRepository.findById(leaseId).orElseThrow(),
                "REFUSE");
        log.info("Imported lease {} refused by tenant {}; cancelled whole", leaseId, callerId);
        return cancel(leaseId, callerId,
                "the tenant refused the imported lease's declared truth");
    }

    /**
     * The vigil's read: an imported backlog cleared inside its window dissolves
     * the borrowed clock and the tenancy continues open-ended.
     * <p>
     * One keyset page of issued RECONVERSION notices at a time, ordered (and
     * resumed) by the notice's own creation instant, each candidate dissolved in
     * its own transaction by {@link #clearReconversion}. A worklist larger than
     * the row budget continues on the next tick instead of being read whole.
     *
     * @return how many reconversions dissolved
     */
    public int clearClearedReconversions() {
        return sweeps.run(SweepJobs.LEASE_VIGIL_RECONVERSIONS,
                (afterKey, afterId, limit) -> noticeRepository.findByTypeAndLeaseStatusPage(
                        NotraiusStatuses.LEASE_NOTICE_RECONVERSION,
                        NotraiusStatuses.LEASE_NOTICE_ISSUED,
                        NotraiusStatuses.LEASE_ACTIVE,
                        afterKey, afterId, PageRequest.of(0, limit)),
                this::clearReconversion);
    }

    /**
     * One issued RECONVERSION notice, in its own transaction. Re-read here and
     * re-guarded: the backlog may have been cleared (or a period skipped) while
     * the pass walked its page, and only the row as it stands may be dissolved.
     *
     * @return 1 when the notice dissolved, 0 when it still has owed periods
     */
    @Transactional
    public int clearReconversion(UUID noticeId) {
        LeaseNotice notice = noticeRepository.findById(noticeId).orElse(null);
        if (notice == null
                || notice.getResponse() != null
                || !NotraiusStatuses.LEASE_NOTICE_RECONVERSION.equals(
                        notice.getType() != null ? notice.getType().getCode() : null)
                || notice.getLease() == null
                || !NotraiusStatuses.LEASE_ACTIVE.equals(
                        notice.getLease().getStatus() != null ? notice.getLease().getStatus().getCode() : null)) {
            return 0;
        }
        Lease lease = notice.getLease();
        if (!NotraiusStatuses.LEASE_ORIGIN_IMPORT.equalsIgnoreCase(lease.getOrigin())) {
            return 0;
        }
        if (!tenantOwed(lease).isEmpty()) {
            return 0;
        }
        stateService.dissolveReconversion(notice);
        return 1;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Internals — rows
    // ═══════════════════════════════════════════════════════════════════════

    private List<LeaseDeposit> writeDeposits(Lease lease, Pricing pricing,
                                             Map<String, LeaseImportRequest.HoldingLine> holdings) {
        Map<String, DepositCommitment> standards = deriveStandards(pricing);
        Map<String, Policy> policies = new LinkedHashMap<>();
        for (Policy policy : policyResolver.applicable(pricing.getProperty().getStateId())) {
            policies.put(policy.getCode(), policy);
        }

        Set<String> codes = new HashSet<>(standards.keySet());
        codes.addAll(holdings.keySet());

        List<LeaseDeposit> deposits = new ArrayList<>();
        BigDecimal monthRent = monthRent(pricing);
        for (String code : codes) {
            DepositCommitment commitment = standards.get(code);
            LeaseImportRequest.HoldingLine holding = holdings.get(code);
            Policy policy = commitment != null ? commitment.policy() : policies.get(code);
            if (policy == null) {
                throw new ConflictException(NotraiusErrors.LEASE_IMPORT_INVALID,
                        "The declared standard '" + code + "' does not govern this property's state");
            }
            Type type = typeRepository.findByEntityCodeAndCode(Entities.LEASE_DEPOSITS, policy.getCode())
                    .orElseThrow(() -> new ConflictException(NotraiusErrors.LEASE_DEPOSIT_TYPE_MISSING,
                            "No lease deposit type is declared for standard '" + code + "'"));

            BigDecimal balance = BigDecimal.ZERO;
            if (holding != null) {
                if (policy.getUnit() == PolicyUnit.AMOUNT) {
                    BigDecimal amount = holding.getAmount();
                    if (amount == null || amount.signum() <= 0) {
                        throw new BadRequestException(NotraiusErrors.LEASE_IMPORT_INVALID,
                                "Holding '" + code + "' declares no positive amount");
                    }
                    balance = monthRent.signum() > 0
                            ? amount.divide(monthRent, 0, RoundingMode.DOWN) : BigDecimal.ZERO;
                } else {
                    balance = holding.getUnits();
                    if (balance == null || balance.signum() <= 0) {
                        throw new BadRequestException(NotraiusErrors.LEASE_IMPORT_INVALID,
                                "Holding '" + code + "' declares no positive units");
                    }
                }
            }

            LeaseDeposit deposit = new LeaseDeposit();
            deposit.setLease(lease);
            deposit.setPolicy(policy);
            deposit.setType(type);
            deposit.setBalance(balance);
            // The standard at import is the policy's own value when the chain
            // answers, else the declaration itself: the notice engine reads this
            // frozen figure forever (6.5).
            deposit.setStandardBalance(commitment != null ? commitment.balance() : balance);
            deposit.setStatus(statusResolver.resolve(Entities.LEASE_DEPOSITS,
                    NotraiusStatuses.LEASE_DEPOSIT_HELD));
            deposit.setVersion(1);
            deposits.add(depositRepository.save(deposit));
        }
        return deposits;
    }

    private void writeDebts(Lease lease, Property property, List<LeaseImportRequest.DebtLine> debts) {
        if (debts == null) {
            return;
        }
        Type installment = typeRepository
                .findByEntityCodeAndCode(Entities.LEASE_BREAKDOWNS, NotraiusStatuses.LEASE_BREAKDOWN_INSTALLMENT)
                .orElseThrow(() -> new IllegalStateException(
                        "No INSTALLMENT breakdown type is seeded for LEASE_BREAKDOWNS"));
        for (LeaseImportRequest.DebtLine line : debts) {
            LeaseBreakdown row = new LeaseBreakdown();
            row.setEntity(statusResolver.resolveEntity(Entities.LEASES));
            row.setReferenceId(lease.getId());
            row.setLease(lease);
            row.setTitle("Imported debt " + line.getPeriodStart());
            row.setAmount(line.getAmount());
            row.setType(installment);
            // Born OVERDUE at its historical date: the record of the past, not a
            // violation of the never-past law, which governs the forward schedule.
            row.setStatus(statusResolver.resolve(Entities.LEASE_BREAKDOWNS,
                    NotraiusStatuses.LEASE_BREAKDOWN_OVERDUE));
            row.setUserId(property.getOwnerId());
            row.setDeadline(line.getPeriodStart());
            row.setPeriodStart(line.getPeriodStart());
            row.setPeriodEnd(line.getPeriodEnd());
            // No period index: the forward calendar's own ordering starts at 1
            // and must never collide with a declared historical month.
            row.setPeriodIndex(null);
            row.setVersion(1);
            row.setCreatedBy(lease.getOwnerId());
            breakdownRepository.save(row);
        }
    }

    private LeaseResponse fundHoldings(Lease lease, Property property, Pricing pricing,
                                       Map<String, LeaseImportRequest.HoldingLine> holdings,
                                       UUID callerId) {
        List<PayPaymentClient.MatrixSplit> wire = new ArrayList<>();
        List<String> wireCodes = new ArrayList<>();
        BigDecimal frozenRent = lease.getRentAmount() != null ? lease.getRentAmount() : pricing.getAmount();
        UUID entityId = statusResolver.resolveEntity(Entities.LEASES).getId();
        for (LeaseImportRequest.HoldingLine line : holdings.values()) {
            Policy policy = policyOf(pricing, line.getPolicyCode());
            boolean amountHull = policy.getUnit() == PolicyUnit.AMOUNT;
            if (amountHull) {
                wire.add(new PayPaymentClient.MatrixSplit(property.getOwnerId(), line.getAmount(),
                        entityId, importSplitReference(lease.getId(), policy.getCode(), 1),
                        "Imported holding " + policy.getCode() + " — lease " + lease.getId()));
                wireCodes.add(policy.getCode());
                continue;
            }
            int units;
            try {
                units = line.getUnits().stripTrailingZeros().intValueExact();
            } catch (ArithmeticException e) {
                throw new BadRequestException(NotraiusErrors.LEASE_IMPORT_INVALID,
                        "Holding '" + policy.getCode() + "' declares a fractional unit");
            }
            for (int unit = 1; unit <= units; unit++) {
                wire.add(new PayPaymentClient.MatrixSplit(property.getOwnerId(), frozenRent,
                        entityId, importSplitReference(lease.getId(), policy.getCode(), unit),
                        "Imported holding " + policy.getCode() + " " + unit + "/" + units
                                + " — lease " + lease.getId()));
                wireCodes.add(policy.getCode());
            }
        }

        PayPaymentClient.PaymentRef payment;
        try {
            payment = payPaymentClient.createCustodialMatrix(callerId, property.getCurrencyId(),
                    NotraiusActivities.Leases.IMPORT_CREATE, lease.getId(), wire);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                throw new ConflictException(NotraiusErrors.LEASE_PAYMENT_REFUSED,
                        "The import's funding was refused (" + e.getStatusCode().value()
                                + "), so no imported lease was created");
            }
            return leaveImportPending(lease, "pay answered " + e.getStatusCode());
        } catch (RuntimeException e) {
            return leaveImportPending(lease, e.getMessage());
        }

        List<PayPaymentClient.SplitInfo> born = payment.splits() != null ? payment.splits() : List.of();
        if (born.size() != wire.size()) {
            return leaveImportPending(lease, "pay returned a split count that does not match the declaration");
        }
        int sequence = 1;
        for (int index = 0; index < wire.size(); index++) {
            PayPaymentClient.SplitInfo split = born.get(index);
            PayPaymentClient.MatrixSplit addressed = wire.get(index);
            LeaseDeposit deposit = depositOf(lease.getId(), wireCodes.get(index));
            LeaseDepositSplit row = new LeaseDepositSplit();
            row.setLease(lease);
            row.setDeposit(deposit);
            row.setPaymentId(payment.id());
            row.setSplitId(split.splitId());
            row.setReferenceId(addressed.referenceId());
            row.setAmount(split.amount() != null ? split.amount() : addressed.amount());
            row.setSequence(sequence++);
            row.setStatus(statusResolver.resolve(Entities.LEASE_DEPOSIT_SPLITS,
                    NotraiusStatuses.LEASE_SPLIT_HELD));
            splitRepository.save(row);
        }
        log.info("Imported lease {} funded {} split(s) by landlord {} (payment {})",
                lease.getId(), wire.size(), callerId, payment.id());
        return read(lease);
    }

    private LeaseDeposit depositOf(UUID leaseId, String policyCode) {
        return depositRepository.findByLeaseIdOrderByPolicyCodeAsc(leaseId).stream()
                .filter(deposit -> deposit.getPolicy() != null
                        && deposit.getPolicy().getCode().equals(policyCode))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No deposit row matches the funded standard '" + policyCode + "'"));
    }

    private Map<String, DepositCommitment> deriveStandards(Pricing pricing) {
        Map<String, DepositCommitment> byCode = new HashMap<>();
        try {
            for (DepositCommitment commitment
                    : derive(pricing).commitments()) {
                byCode.put(commitment.policy().getCode(), commitment);
            }
        } catch (ConflictException e) {
            // A historical pricing may no longer answer the live chain. The
            // declaration itself is then the standard (6.6's frozen truth).
            log.warn("Import on pricing {} derives no live standard ({}); declared holdings govern",
                    pricing.getId(), e.getMessage());
        }
        return byCode;
    }

    private Policy policyOf(Pricing pricing, String code) {
        return policyResolver.applicable(pricing.getProperty().getStateId()).stream()
                .filter(policy -> policy.getCode().equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new ConflictException(NotraiusErrors.LEASE_IMPORT_INVALID,
                        "The declared standard '" + code + "' does not govern this property's state"));
    }

    private BigDecimal monthRent(Pricing pricing) {
        try {
            return rentPerMonth(pricing);
        } catch (RuntimeException e) {
            return pricing.getAmount();
        }
    }

    private void validateDebts(List<LeaseImportRequest.DebtLine> debts, Instant from, Instant to) {
        if (debts == null) {
            return;
        }
        Set<Instant> starts = new HashSet<>();
        for (LeaseImportRequest.DebtLine line : debts) {
            if (line.getPeriodStart() == null || line.getPeriodEnd() == null
                    || line.getAmount() == null || line.getAmount().signum() <= 0) {
                throw new BadRequestException(NotraiusErrors.LEASE_IMPORT_INVALID,
                        "A declared debt needs both period bounds and a positive amount");
            }
            if (!line.getPeriodEnd().isAfter(line.getPeriodStart())) {
                throw new BadRequestException(NotraiusErrors.LEASE_IMPORT_PERIOD_INVALID,
                        "Declared period [" + line.getPeriodStart() + ", " + line.getPeriodEnd()
                                + ") has no length");
            }
            if (line.getPeriodStart().isBefore(from)
                    || (to != null && line.getPeriodEnd().isAfter(to))) {
                throw new BadRequestException(NotraiusErrors.LEASE_IMPORT_PERIOD_INVALID,
                        "Declared period [" + line.getPeriodStart() + ", " + line.getPeriodEnd()
                                + ") lies outside the tenancy's window [" + from + ", " + to + ")");
            }
            if (!starts.add(line.getPeriodStart())) {
                throw new BadRequestException(NotraiusErrors.LEASE_IMPORT_PERIOD_INVALID,
                        "Two declared debts cover the same period starting " + line.getPeriodStart());
            }
        }
    }

    private Map<String, LeaseImportRequest.HoldingLine> indexHoldings(List<LeaseImportRequest.HoldingLine> holdings) {
        Map<String, LeaseImportRequest.HoldingLine> byCode = new LinkedHashMap<>();
        if (holdings == null) {
            return byCode;
        }
        for (LeaseImportRequest.HoldingLine line : holdings) {
            if (line.getPolicyCode() == null || line.getPolicyCode().isBlank()) {
                throw new BadRequestException(NotraiusErrors.LEASE_IMPORT_INVALID,
                        "A declared holding must name its standard");
            }
            String code = line.getPolicyCode().trim().toUpperCase();
            if (byCode.putIfAbsent(code, line) != null) {
                throw new BadRequestException(NotraiusErrors.LEASE_IMPORT_INVALID,
                        "Two declared holdings name the same standard '" + code + "'");
            }
        }
        return byCode;
    }

    private Lease requireImport(UUID leaseId, UUID callerId) {
        Lease lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new NotFoundException(NotraiusErrors.LEASE_NOT_FOUND,
                        "Lease not found: " + leaseId));
        if (!NotraiusStatuses.LEASE_ORIGIN_IMPORT.equalsIgnoreCase(lease.getOrigin())) {
            throw new ConflictException(NotraiusErrors.LEASE_IMPORT_REFUSED,
                    "Lease " + leaseId + " is not an imported lease");
        }
        if (!NotraiusStatuses.LEASE_PENDING.equalsIgnoreCase(
                lease.getStatus() != null ? lease.getStatus().getCode() : null)) {
            throw new ConflictException(NotraiusErrors.LEASE_IMPORT_REFUSED,
                    "Lease " + leaseId + " is " + lease.getStatus().getCode()
                            + ", not PENDING for verification");
        }
        if (callerId == null || !callerId.equals(lease.getUserId())) {
            throw new ForbiddenException(NotraiusErrors.LEASE_IMPORT_REFUSED,
                    "Only the imported lease's tenant may verify it");
        }
        return lease;
    }

    /**
     * A declared holding whose funding never answered keeps the import PENDING:
     * accepting it would make the tenant verify a custody nobody can find.
     */
    private void requireFundingResolved(Lease lease) {
        boolean declared = depositRepository.findByLeaseIdOrderByPolicyCodeAsc(lease.getId()).stream()
                .anyMatch(deposit -> deposit.getBalance() != null
                        && deposit.getBalance().signum() > 0);
        if (declared && splitRepository.findByLeaseIdOrderBySequenceAsc(lease.getId()).isEmpty()) {
            throw new ConflictException(NotraiusErrors.LEASE_FUNDING_UNRESOLVED,
                    "The import's declared holdings were never funded; nothing can be verified");
        }
    }

    private List<LeaseBreakdown> tenantOwed(Lease lease) {
        return breakdownRepository.findTenantOwed(lease.getId(),
                List.of(NotraiusStatuses.LEASE_BREAKDOWN_PENDING,
                        NotraiusStatuses.LEASE_BREAKDOWN_OVERDUE),
                Entities.LEASES, Entities.LEASE_REPAIRS, lease.getUserId());
    }

    private LeaseResponse leaveImportPending(Lease lease, String reason) {
        log.warn("Imported lease {} funding outcome unknown ({}); left PENDING", lease.getId(), reason);
        auditImport(NotraiusActivities.Leases.FUND_UNKNOWN, lease, "UNKNOWN");
        return read(lease);
    }

    private static UUID importSplitReference(UUID leaseId, String policyCode, int unit) {
        return UUID.nameUUIDFromBytes(
                (IMPORT_SPLIT_REFERENCE_PREFIX + leaseId + ":" + policyCode + ":" + unit)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private void auditImport(String activityCode, Lease lease, String operation) {
        auditTrail.record(Entities.LEASES, activityCode, lease.getId(), operation, null, Map.of(
                "leaseId", lease.getId().toString(),
                "origin", String.valueOf(lease.getOrigin())));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LeaseScheduleService
    // ═══════════════════════════════════════════════════════════════════════

    /*
     * The lease's calendar made real (PLAN 6.2's installments).
     * <p>
     * <b>One row per period of the cadence</b>, carrying its amount (the rent frozen
     * at birth, never re-derived), its deadline, its period and its recipient — the
     * property's owner at the instant of generation, because new money takes new
     * truth while born custody keeps its own (6.4). The row's type is INSTALLMENT
     * for rent; electricity and whatever else the cadence bills ride other types
     * from the same machinery.
     * <p>
     * <b>One synchronization covers three acts</b>, because they are the same law
     * read in three directions: the first generation at activation; the rolling
     * horizon's extension for an open-ended tenancy; and the reel that cuts away
     * periods a stamped validity no longer grants. Unpaid future periods beyond the
     * grant are CANCELLED — they carried no money, so nothing is written off; a paid
     * period the horizon cuts away is the settlement's unearned-rent debt (6.5) and
     * is never touched here, because money history is append-only.
     */

    /**
     * The vigil's per-row door: load and sync in one transaction, so a failure on
     * one lease's calendar never poisons another's tick.
     */
    @Transactional
    public int syncById(UUID leaseId) {
        return leaseRepository.findById(leaseId).map(this::sync).orElse(0);
    }

    /**
     * Bring the calendar in line with the grant as it stands now: generate what
     * is missing, cancel unpaid periods the grant no longer holds.
     *
     * @return how many rows this act wrote (generated or cut)
     */
    @Transactional
    public int sync(Lease lease) {
        Instant now = Instant.now();
        Instant from = lease.getValidity().getFromDate();
        Instant to = lease.getValidity().getToDate();
        int horizon = properties.getLeaseScheduleHorizonPeriods();

        List<Period> allowed = periods(from, to, now, horizon,
                lease.getDuration(), 1);

        Set<Integer> existing = breakdownRepository
                .findByLeaseIdAndPeriodIndexIsNotNullOrderByPeriodIndexAsc(lease.getId())
                .stream().map(LeaseBreakdown::getPeriodIndex).collect(Collectors.toCollection(HashSet::new));

        BigDecimal rent = rentOf(lease);
        UUID recipient = recipientOf(lease);
        Type installment = typeRepository
                .findByEntityCodeAndCode(Entities.LEASE_BREAKDOWNS, NotraiusStatuses.LEASE_BREAKDOWN_INSTALLMENT)
                .orElseThrow(() -> new IllegalStateException(
                        "No INSTALLMENT breakdown type is seeded for LEASE_BREAKDOWNS"));

        int written = 0;
        for (Period period : allowed) {
            if (existing.contains(period.index())) {
                continue;
            }
            breakdownRepository.save(breakdown(lease, installment, rent, recipient, period, now));
            written++;
        }

        for (LeaseBreakdown row : breakdownRepository.findByLeaseIdAndStatusCodes(lease.getId(),
                List.of(NotraiusStatuses.LEASE_BREAKDOWN_PENDING))) {
            if (row.getPeriodIndex() == null || row.getPeriodStart() == null) {
                continue;
            }
            if (to != null && row.getPeriodStart().isAfter(to)) {
                // No money ever stood against this period: it is not a write-off.
                row.setStatus(statusResolver.resolve(Entities.LEASE_BREAKDOWNS,
                        NotraiusStatuses.LEASE_BREAKDOWN_CANCELLED));
                row.setVersion(row.getVersion() + 1);
                breakdownRepository.save(row);
                written++;
            }
        }

        if (written > 0) {
            auditSchedule(existing.isEmpty() ? NotraiusActivities.Leases.SCHEDULE
                    : NotraiusActivities.Leases.SCHEDULE_EXTEND, lease, written);
            log.info("Lease {} calendar synced: {} rows ({} periods allowed, horizon {})",
                    lease.getId(), written, allowed.size(), horizon);
        }
        return written;
    }

    private LeaseBreakdown breakdown(Lease lease, Type installment, BigDecimal rent, UUID recipient,
                                     Period period, Instant now) {
        LeaseBreakdown row = new LeaseBreakdown();
        row.setEntity(statusResolver.resolveEntity(Entities.LEASES));
        row.setReferenceId(lease.getId());
        row.setLease(lease);
        row.setTitle("Installment " + period.index());
        row.setAmount(rent);
        row.setType(installment);
        row.setStatus(statusResolver.resolve(Entities.LEASE_BREAKDOWNS,
                NotraiusStatuses.LEASE_BREAKDOWN_PENDING));
        row.setUserId(recipient);
        row.setDeadline(period.deadline());
        row.setPeriodStart(period.start());
        row.setPeriodEnd(period.end());
        row.setPeriodIndex(period.index());
        row.setVersion(1);
        row.setCreatedBy(lease.getUserId());
        if (!period.deadline().equals(period.start())) {
            log.info("Lease {} period {} born due now (its start {} already passed)",
                    lease.getId(), period.index(), period.start());
        }
        return row;
    }

    /**
     * The frozen rent: what the tenancy was funded against, never today's price.
     * <p>
     * <b>The fallback is gone on purpose.</b> Every birth path freezes the figure
     * (6.1/6.2), so a null here is a row that predates the freeze — and the only
     * thing the pricing could answer is today's amount, which is exactly the
     * wrong-version answer this method exists to prevent. A missing term refuses
     * loudly; it never silently reprices a contract.
     */
    public BigDecimal rentOf(Lease lease) {
        if (lease.getRentAmount() != null) {
            return lease.getRentAmount();
        }
        throw new ConflictException(NotraiusErrors.LEASE_STATE_INVALID,
                "Lease " + lease.getId() + " carries no frozen rent; its price was never recorded "
                        + "at birth and the live pricing would answer a different contract");
    }

    /**
     * Who new money pays: the property's owner at this generation. Born custody
     * keeps its own beneficiaries (6.4) — this is only for rows created now.
     */
    private UUID recipientOf(Lease lease) {
        UUID owner = lease.getPricing().getProperty() != null
                ? lease.getPricing().getProperty().getOwnerId() : null;
        return owner != null ? owner : lease.getOwnerId();
    }

    private void auditSchedule(String activityCode, Lease lease, int written) {
        auditTrail.record(Entities.LEASES, activityCode, lease.getId(), "SYNC", null, Map.of(
                "leaseId", lease.getId().toString(),
                "rowsWritten", written));
    }

}
