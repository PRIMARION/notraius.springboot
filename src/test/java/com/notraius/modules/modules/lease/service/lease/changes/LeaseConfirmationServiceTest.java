package com.notraius.modules.modules.lease.service.lease.changes;

import com.notraius.common.NotraiusErrors;
import com.notraius.common.services.logs.StatusResolver;
import com.notraius.database.entities.asset.asset.Pricing;
import com.notraius.database.entities.asset.asset.Property;
import com.notraius.database.entities.lease.lease.records.Lease;
import com.notraius.database.entities.lease.reference.status.Status;
import com.notraius.database.entities.lease.reference.amenity.Validity;
import com.notraius.database.repositories.lease.lease.lease.LeaseDepositRepository;
import com.notraius.database.repositories.lease.lease.lease.LeaseDepositSplitRepository;
import com.notraius.database.repositories.lease.lease.lease.LeaseRepository;
import com.notraius.modules.core.core.common.constants.NotraiusStatuses;
import com.notraius.modules.lease.lease.agreement.dto.response.LeaseResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.primarion.module.app.access.consent.AuditLogWriter;
import org.primarion.module.communication.errors.exception.AppException;
import org.primarion.module.communication.errors.client.ConflictException;
import org.primarion.module.communication.errors.core.ErrorCode;
import org.primarion.module.communication.errors.client.ForbiddenException;
import org.primarion.module.utils.utils.security.security.users.user.UserService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.notraius.modules.lease.lease.agreement.service.LeaseAgreementService;
import com.notraius.modules.modules.lease.service.lease.agreement.LeaseAgreementServiceFixture;

/**
 * The owner's commitment (PLAN 6.2): only the beneficiary confirms, the identity
 * is re-read, and the checkable boolean decides whether the condition stage
 * stands between CONFIRMED and ACTIVE.
 */
@ExtendWith(MockitoExtension.class)
class LeaseConfirmationServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID STRANGER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private LeaseRepository leaseRepository;
    @Mock private LeaseDepositRepository depositRepository;
    @Mock private LeaseDepositSplitRepository splitRepository;
    @Mock private StatusResolver statusResolver;
    @Mock private UserService userService;
    @Mock private AuditLogWriter auditLogWriter;

    private LeaseAgreementService service;

    @BeforeEach
    void setUp() {
        service = spy(LeaseAgreementServiceFixture.aService()
                .leaseRepository(leaseRepository)
                .depositRepository(depositRepository)
                .splitRepository(splitRepository)
                .statusResolver(statusResolver)
                .userService(userService)
                .auditLogWriter(auditLogWriter)
                .build());
        lenient().when(statusResolver.resolve(any(), any()))
                .thenAnswer(invocation -> status(invocation.getArgument(1)));
        lenient().when(leaseRepository.save(any(Lease.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(depositRepository.findByLeaseIdOrderByPolicyCodeAsc(any()))
                .thenReturn(List.of());
        lenient().when(splitRepository.findByLeaseIdOrderBySequenceAsc(any()))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("a checkable tenancy parks at CONFIRMED and waits for its conditions")
    void checkableParksAtConfirmed() {
        Lease lease = lease(true);
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));
        when(userService.isKycVerified(TENANT)).thenReturn(true);

        LeaseResponse response = service.confirm(lease.getId(), OWNER);

        assertThat(response.getStatus()).isEqualTo(NotraiusStatuses.LEASE_CONFIRMED);
        assertThat(lease.getValidity().getStatus().getCode())
                .isEqualTo(NotraiusStatuses.LEASE_CONFIRMED);
        verify(service, never()).activate(any(), any(), any());
    }

    @Test
    @DisplayName("an unchecked pricing bypasses the condition stage and activates at once")
    void uncheckedActivates() {
        Lease lease = lease(false);
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));
        when(userService.isKycVerified(TENANT)).thenReturn(true);
        LeaseResponse active = new LeaseResponse();
        active.setStatus(NotraiusStatuses.LEASE_ACTIVE);
        doReturn(active).when(service).activate(any(), any(), any());

        LeaseResponse response = service.confirm(lease.getId(), OWNER);

        assertThat(response.getStatus()).isEqualTo(NotraiusStatuses.LEASE_ACTIVE);
        verify(service).activate(any(), any(), any());
    }

    @Test
    @DisplayName("only the beneficiary confirms")
    void onlyOwnerConfirms() {
        Lease lease = lease(true);
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));

        assertThatThrownBy(() -> service.confirm(lease.getId(), STRANGER))
                .isInstanceOf(ForbiddenException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.LEASE_CONFIRM_FORBIDDEN));
    }

    @Test
    @DisplayName("the tenant's identity is re-read at commitment")
    void kycIsReRead() {
        Lease lease = lease(true);
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));
        when(userService.isKycVerified(TENANT)).thenReturn(false);

        assertThatThrownBy(() -> service.confirm(lease.getId(), OWNER))
                .isInstanceOf(ForbiddenException.class)
                .satisfies(t -> assertThat(errorOf(t)).isEqualTo(NotraiusErrors.LEASE_KYC_REQUIRED));
        assertThat(lease.getStatus().getCode()).isEqualTo(NotraiusStatuses.LEASE_PENDING);
    }

    @Test
    @DisplayName("a committed lease cannot be confirmed again")
    void onlyPendingConfirms() {
        Lease lease = lease(true);
        lease.setStatus(status(NotraiusStatuses.LEASE_CONFIRMED));
        when(leaseRepository.findById(lease.getId())).thenReturn(Optional.of(lease));

        assertThatThrownBy(() -> service.confirm(lease.getId(), OWNER))
                .isInstanceOf(ConflictException.class)
                .satisfies(t -> assertThat(errorOf(t))
                        .isEqualTo(NotraiusErrors.LEASE_CONFIRM_WINDOW_CLOSED));
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private Lease lease(boolean checkable) {
        Property property = new Property();
        property.setId(UUID.randomUUID());
        property.setTitle("Villa");
        property.setOwnerId(OWNER);
        property.setStatus(status(NotraiusStatuses.PROPERTY_LEASED));

        Pricing pricing = new Pricing();
        pricing.setId(UUID.randomUUID());
        pricing.setProperty(property);
        pricing.setAmount(new BigDecimal("50.00"));
        pricing.setCheckable(checkable);

        Validity validity = new Validity();
        validity.setId(UUID.randomUUID());
        validity.setFromDate(Instant.now());
        validity.setStatus(status(NotraiusStatuses.LEASE_PENDING));

        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        lease.setUserId(TENANT);
        lease.setOwnerId(OWNER);
        lease.setPricing(pricing);
        lease.setCheckable(checkable);
        lease.setDuration(pricing.getDuration());
        lease.setValidity(validity);
        lease.setStatus(status(NotraiusStatuses.LEASE_PENDING));
        lease.setOrigin(NotraiusStatuses.LEASE_ORIGIN_NATIVE);
        lease.setVersion(1);
        return lease;
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
