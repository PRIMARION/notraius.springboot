package com.notraius.modules.modules.lease.service.lease.agreement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.integration.pay.PayPaymentClient;
import com.notraius.common.integration.pay.ReputationAssessmentClient;
import com.notraius.common.services.logs.StatusResolver;
import com.notraius.database.repositories.lease.lease.records.LeaseBreakdownRepository;
import com.notraius.database.repositories.lease.lease.lease.LeaseDepositRepository;
import com.notraius.database.repositories.lease.lease.lease.LeaseDepositSplitRepository;
import com.notraius.database.repositories.lease.lease.lease.LeaseRepository;
import com.notraius.database.repositories.lease.lease.records.LeaseTopUpRepository;
import com.notraius.modules.lease.lease.finance.LeaseFinanceMapper;
import com.notraius.modules.lease.lease.state.LeaseStateService;
import org.primarion.module.app.access.consent.database.ActivityRepository;
import org.primarion.module.app.access.consent.AuditLogWriter;

import static org.mockito.Mockito.mock;
import com.notraius.common.services.visit.LeaseAnnouncer;
import com.notraius.modules.lease.lease.finance.LeaseFinanceService;

/**
 * The merged finance service's fourteen doors. A test hands over the few it
 * drives and leaves the rest mocked: one service means one constructor, and no
 * test should care about the others' collaborators.
 */
public final class LeaseFinanceServiceFixture {

    private LeaseBreakdownRepository breakdownRepository = mock(LeaseBreakdownRepository.class);
    private LeaseDepositRepository depositRepository = mock(LeaseDepositRepository.class);
    private LeaseDepositSplitRepository splitRepository = mock(LeaseDepositSplitRepository.class);
    private LeaseRepository leaseRepository = mock(LeaseRepository.class);
    private LeaseTopUpRepository topUpRepository = mock(LeaseTopUpRepository.class);
    private PayPaymentClient payPaymentClient = mock(PayPaymentClient.class);
    private ReputationAssessmentClient assessmentClient = mock(ReputationAssessmentClient.class);
    private ActivityRepository activityRepository = mock(ActivityRepository.class);
    private StatusResolver statusResolver = mock(StatusResolver.class);
    private LeaseStateService stateService = mock(LeaseStateService.class);
    private LeaseFinanceMapper financeMapper = new LeaseFinanceMapper();
    private AuditLogWriter auditLogWriter = mock(AuditLogWriter.class);
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private LeaseFinanceServiceFixture() {
    }

    public static LeaseFinanceServiceFixture aService() {
        return new LeaseFinanceServiceFixture();
    }

    public LeaseFinanceServiceFixture breakdownRepository(LeaseBreakdownRepository value) {
        this.breakdownRepository = value;
        return this;
    }

    public LeaseFinanceServiceFixture depositRepository(LeaseDepositRepository value) {
        this.depositRepository = value;
        return this;
    }

    public LeaseFinanceServiceFixture splitRepository(LeaseDepositSplitRepository value) {
        this.splitRepository = value;
        return this;
    }

    public LeaseFinanceServiceFixture leaseRepository(LeaseRepository value) {
        this.leaseRepository = value;
        return this;
    }

    public LeaseFinanceServiceFixture topUpRepository(LeaseTopUpRepository value) {
        this.topUpRepository = value;
        return this;
    }

    public LeaseFinanceServiceFixture payPaymentClient(PayPaymentClient value) {
        this.payPaymentClient = value;
        return this;
    }

    public LeaseFinanceServiceFixture assessmentClient(ReputationAssessmentClient value) {
        this.assessmentClient = value;
        return this;
    }

    public LeaseFinanceServiceFixture activityRepository(ActivityRepository value) {
        this.activityRepository = value;
        return this;
    }

    public LeaseFinanceServiceFixture statusResolver(StatusResolver value) {
        this.statusResolver = value;
        return this;
    }

    public LeaseFinanceServiceFixture stateService(LeaseStateService value) {
        this.stateService = value;
        return this;
    }

    public LeaseFinanceServiceFixture auditLogWriter(AuditLogWriter value) {
        this.auditLogWriter = value;
        return this;
    }

    public LeaseFinanceService build() {
        return new LeaseFinanceService(breakdownRepository, depositRepository, splitRepository,
                leaseRepository, topUpRepository, payPaymentClient,
                assessmentClient, activityRepository, statusResolver, stateService,
                financeMapper, new AuditTrail(objectMapper, auditLogWriter),
                new com.notraius.common.services.visit.LeaseAnnouncer(null, null, objectMapper));
    }
}
