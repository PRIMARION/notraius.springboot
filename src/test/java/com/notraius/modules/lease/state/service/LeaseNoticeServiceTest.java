package com.notraius.modules.lease.state.service;

import com.notraius.common.NotraiusErrors;
import com.notraius.common.services.StatusResolver;
import com.notraius.config.properties.NotraiusProperties;
import com.notraius.database.entities.asset.Pricing;
import com.notraius.database.entities.asset.Property;
import com.notraius.database.entities.config.Policy;
import com.notraius.database.entities.config.PolicyUnit;
import com.notraius.database.entities.lease.Lease;
import com.notraius.database.entities.lease.LeaseDeposit;
import com.notraius.database.entities.lease.LeaseNotice;
import com.notraius.database.entities.reference.Duration;
import com.notraius.database.entities.reference.Status;
import com.notraius.database.entities.reference.Type;
import com.notraius.database.entities.reference.Validity;
import com.notraius.database.repositories.lease.LeaseDepositRepository;
import com.notraius.database.repositories.lease.LeaseNoticeRepository;
import com.notraius.database.repositories.lease.LeaseRepository;
import com.notraius.database.repositories.reference.TypeRepository;
import com.notraius.modules.core.common.constants.NotraiusStatuses;
import com.notraius.modules.lease.agreement.service.LeaseAgreementService;
import com.notraius.modules.lease.state.dto.response.LeaseNoticeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.primarion.module.access.audit.AuditLogWriter;
import org.primarion.module.errors.AppException;
import org.primarion.module.errors.ErrorCode;
import org.primarion.module.errors.ForbiddenException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The notice engine (PLAN 6.5): a thin cushion warns, acceptance buys a cadence
 * of grace, refusal escalates at once, silence escalates by the clock, and the
 * landlord's cancel restores exactly the end the stamp overwrote.
 */
@ExtendWith(MockitoExtension.class)
class LeaseNoticeServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID STRANGER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private LeaseNoticeRepository noticeRepository;
    @Mock private LeaseRepository leaseRepository;
    @Mock private LeaseDepositRepository depositRepository;
    @Mock private LeaseAgreementService scheduleService;
    @Mock private TypeRepository typeRepository;
    @Mock private StatusResolver statusResolver;
    @Mock private AuditLogWriter auditLogWriter;

    private NotraiusProperties properties;
    private LeaseStateService service;

    @BeforeEach
    void setUp() {
        properties = new NotraiusProperties();
        service = LeaseStateServiceFixture.aService()
                .noticeRepository(noticeRepository)
                .leaseRepository(leaseRepository)
                .depositRepository(depositRepository)
                .agreementService(scheduleService)
                .typeRepository(typeRepository)
                .statusResolver(statusResolver)
                .properties(properties)
                .auditLogWriter(auditLogWriter)
                .build();
        lenient().when(statusResolver.resolve(any(), any()))
                .thenAnswer(invocation -> status(invocation.getArgument(1)));
        lenient().when(statusResolver.openValidity(any(), any()))
                .thenAnswer(invocation -> {
                    Validity validity = new Validity();
                    validity.setId(UUID.randomUUID());
                    validity.setFromDate(Instant.now());
                    return validity;
                });
        lenient().when(noticeRepository.save(any(LeaseNotice.class)))
                .thenAnswer(i -> i.getArgument(0));
        lenient().when(leaseRepository.save(any(Lease.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(typeRepository.findByEntityCodeAndCode(any(), any()))
                .thenAnswer(invocation -> {
                    Type type = new Type();
                    type.setId(UUID.randomUUID());
                    type.setCode(invocation.getArgument(1));
                    return Optional.of(type);
                });
    }

    @Test
    @DisplayName("a cushion of one unit issues a DEPOSIT notice with the response window")
    void thinCushionWarns() {
        Lease lease = lease(NotraiusStatuses.LEASE_ACTIVE);
        LeaseDeposit advance = advance(lease, new BigDecimal("1"), new BigDecimal("4"));
        when(depositRepository.findByLeaseIdOrderByPolicyCodeAsc(lease.getId()))
                .thenReturn(List.of(advance));
        Optional<LeaseNotice> issued = service.evaluateDeposit(lease);

        assertThat(issued).isPresent();
        assertThat(issued.get().getType().getCode())
                .isEqualTo(NotraiusStatuses.LEASE_NOTICE_DEPOSIT);
        long deltaMinutes = Math.abs(java.time.Duration.between(
                Instant.now().plus(properties.getLeaseNoticeResponseHours(), ChronoUnit.HOURS),
                issued.get().getValidity().getToDate()).toMinutes());
        assertThat(deltaMinutes).isLessThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("a cushion back at its standard closes the open notice satisfied")
    void fullCushionSatisfies() {
        Lease lease = lease(NotraiusStatuses.LEASE_ACTIVE);
        LeaseDeposit advance = advance(lease, new BigDecimal("4"), new BigDecimal("4"));
        LeaseNotice open = notice(lease, NotraiusStatuses.LEASE_NOTICE_DEPOSIT,
                Instant.now().plusSeconds(3600));
        when(depositRepository.findByLeaseIdOrderByPolicyCodeAsc(lease.getId()))
                .thenReturn(List.of(advance));
        when(noticeRepository.findOpenOfType(org.mockito.ArgumentMatchers.eq(lease.getId()),
                org.mockito.ArgumentMatchers.eq(NotraiusStatuses.LEASE_NOTICE_DEPOSIT),
                any())).thenReturn(List.of(open));

        Optional<LeaseNotice> result = service.evaluateDeposit(lease);

        assertThat(result).contains(open);
        assertThat(open.getResponse()).isEqualTo(NotraiusStatuses.LEASE_RESPONSE_SATISFIED);
        assertThat(open.getValidity().getStatus().getCode())
                .isEqualTo(NotraiusStatuses.LEASE_NOTICE_SATISFIED);
    }

    @Test
    @DisplayName("acceptance opens a grace of the frozen standard × the multiplier, in the pricing's cadence")
    void acceptanceBuysCadenceGrace() {
        Lease lease = lease(NotraiusStatuses.LEASE_ACTIVE);
        LeaseDeposit advance = advance(lease, new BigDecimal("1"), new BigDecimal("4"));
        LeaseNotice notice = notice(lease, NotraiusStatuses.LEASE_NOTICE_DEPOSIT,
                Instant.now().plusSeconds(3600));
        when(noticeRepository.findById(notice.getId())).thenReturn(Optional.of(notice));
        when(depositRepository.findByLeaseIdOrderByPolicyCodeAsc(lease.getId()))
                .thenReturn(List.of(advance));

        LeaseNoticeResponse response = service.accept(notice.getId(), TENANT);

        long graceUnits = (long) properties.getLeaseDepositGraceMultiplier() * 4;
        Instant expected = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                .plusMonths(graceUnits).toInstant();
        assertThat(response.getResponse()).isEqualTo(NotraiusStatuses.LEASE_RESPONSE_ACCEPT);
        assertThat(notice.getValidity().getStatus().getCode())
                .isEqualTo(NotraiusStatuses.LEASE_NOTICE_ACCEPTED);
        assertThat(Math.abs(java.time.Duration.between(expected,
                notice.getValidity().getToDate()).toMinutes())).isLessThanOrEqualTo(2L);
    }

    @Test
    @DisplayName("refusal escalates at once: a VACATION stamps the tenancy's end")
    void refusalEscalatesImmediately() {
        Lease lease = lease(NotraiusStatuses.LEASE_ACTIVE);
        LeaseNotice notice = notice(lease, NotraiusStatuses.LEASE_NOTICE_DEPOSIT,
                Instant.now().plusSeconds(3600));
        when(noticeRepository.findById(notice.getId())).thenReturn(Optional.of(notice));

        service.refuse(notice.getId(), TENANT);

        assertThat(notice.getResponse()).isEqualTo(NotraiusStatuses.LEASE_RESPONSE_REFUSE);
        assertThat(lease.getValidity().getToDate()).isNotNull();
        assertThat(lease.getValidity().getToDate())
                .isAfter(Instant.now().plus(80, ChronoUnit.DAYS))
                .isBefore(Instant.now().plus(95, ChronoUnit.DAYS));
        verify(scheduleService).sync(lease);
    }

    @Test
    @DisplayName("the landlord's cancel restores exactly the end the vacation overwrote")
    void cancelRestoresOpenEnded() {
        Lease lease = lease(NotraiusStatuses.LEASE_ACTIVE);
        LeaseNotice vacation = notice(lease, NotraiusStatuses.LEASE_NOTICE_VACATION,
                Instant.now().plus(30, ChronoUnit.DAYS));
        vacation.setPreviousValidityToDate(null);
        lease.getValidity().setToDate(Instant.now().plus(30, ChronoUnit.DAYS));
        when(noticeRepository.findById(vacation.getId())).thenReturn(Optional.of(vacation));
        when(depositRepository.findByLeaseIdOrderByPolicyCodeAsc(lease.getId()))
                .thenReturn(List.of());

        service.cancel(vacation.getId(), OWNER);

        assertThat(vacation.getResponse()).isEqualTo(NotraiusStatuses.LEASE_RESPONSE_CANCEL);
        assertThat(lease.getValidity().getToDate()).isNull();
        verify(scheduleService).sync(lease);
    }

    @Test
    @DisplayName("only the tenant answers, and only the owner cancels")
    void partiesAreSeparated() {
        Lease lease = lease(NotraiusStatuses.LEASE_ACTIVE);
        LeaseNotice deposit = notice(lease, NotraiusStatuses.LEASE_NOTICE_DEPOSIT,
                Instant.now().plusSeconds(3600));
        LeaseNotice vacation = notice(lease, NotraiusStatuses.LEASE_NOTICE_VACATION,
                Instant.now().plusSeconds(3600));
        when(noticeRepository.findById(deposit.getId())).thenReturn(Optional.of(deposit));
        when(noticeRepository.findById(vacation.getId())).thenReturn(Optional.of(vacation));

        assertThatThrownBy(() -> service.accept(deposit.getId(), STRANGER))
                .isInstanceOf(ForbiddenException.class)
                .satisfies(t -> assertThat(errorOf(t))
                        .isEqualTo(NotraiusErrors.LEASE_NOTICE_FORBIDDEN));
        assertThatThrownBy(() -> service.cancel(vacation.getId(), TENANT))
                .isInstanceOf(ForbiddenException.class)
                .satisfies(t -> assertThat(errorOf(t))
                        .isEqualTo(NotraiusErrors.LEASE_NOTICE_FORBIDDEN));
    }

    @Test
    @DisplayName("a disputed lease gets no new notices — forced actions freeze")
    void disputedLeaseIssuesNothing() {
        Lease lease = lease(NotraiusStatuses.LEASE_DISPUTED);

        assertThat(service.evaluateDeposit(lease)).isEmpty();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private Lease lease(String statusCode) {
        Property property = new Property();
        property.setId(UUID.randomUUID());
        property.setOwnerId(OWNER);
        property.setStatus(status(NotraiusStatuses.PROPERTY_LEASED));

        Type month = new Type();
        month.setId(UUID.randomUUID());
        month.setCode("MONTH");
        Duration duration = new Duration();
        duration.setId(UUID.randomUUID());
        duration.setType(month);
        duration.setValue(1);

        Pricing pricing = new Pricing();
        pricing.setId(UUID.randomUUID());
        pricing.setProperty(property);
        pricing.setAmount(new BigDecimal("50.00"));
        pricing.setDuration(duration);

        Validity validity = new Validity();
        validity.setId(UUID.randomUUID());
        validity.setFromDate(Instant.now().minus(30, ChronoUnit.DAYS));
        validity.setStatus(status(NotraiusStatuses.LEASE_ACTIVE));

        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        lease.setUserId(TENANT);
        lease.setOwnerId(OWNER);
        lease.setPricing(pricing);
        lease.setCheckable(pricing.isCheckable());
        lease.setDuration(pricing.getDuration());
        lease.setValidity(validity);
        lease.setStatus(status(statusCode));
        lease.setOrigin(NotraiusStatuses.LEASE_ORIGIN_NATIVE);
        lease.setVersion(1);
        return lease;
    }

    private LeaseDeposit advance(Lease lease, BigDecimal balance, BigDecimal standard) {
        Policy policy = new Policy();
        policy.setId(UUID.randomUUID());
        policy.setCode("ADVANCE");
        policy.setUnit(PolicyUnit.UNITS);

        Type type = new Type();
        type.setId(UUID.randomUUID());
        type.setCode(NotraiusStatuses.LEASE_DEPOSIT_ADVANCE);

        LeaseDeposit deposit = new LeaseDeposit();
        deposit.setId(UUID.randomUUID());
        deposit.setLease(lease);
        deposit.setPolicy(policy);
        deposit.setType(type);
        deposit.setBalance(balance);
        deposit.setStandardBalance(standard);
        deposit.setStatus(status(NotraiusStatuses.LEASE_DEPOSIT_HELD));
        deposit.setVersion(1);
        return deposit;
    }

    private LeaseNotice notice(Lease lease, String typeCode, Instant until) {
        Type type = new Type();
        type.setId(UUID.randomUUID());
        type.setCode(typeCode);

        Validity validity = new Validity();
        validity.setId(UUID.randomUUID());
        validity.setFromDate(Instant.now());
        validity.setToDate(until);
        validity.setStatus(status(NotraiusStatuses.LEASE_NOTICE_ISSUED));

        LeaseNotice notice = new LeaseNotice();
        notice.setId(UUID.randomUUID());
        notice.setLease(lease);
        notice.setType(type);
        notice.setValidity(validity);
        notice.setReason("test");
        notice.setVersion(1);
        return notice;
    }

    private Status status(String code) {
        Status status = new Status();
        status.setId(UUID.randomUUID());
        status.setCode(code);
        return status;
    }

    private static ErrorCode errorOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(AppException.class);
        return ((AppException) thrown).getError();
    }
}
