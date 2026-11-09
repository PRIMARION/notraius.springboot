package com.notraius.modules.lease.finance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.integration.PayPaymentClient;
import com.notraius.common.integration.ReputationAssessmentClient;
import com.notraius.common.services.StatusResolver;
import com.notraius.database.repositories.asset.PropertyPolicyValueRepository;
import com.notraius.database.repositories.lease.LeaseBreakdownRepository;
import com.notraius.database.repositories.lease.LeaseDepositRepository;
import com.notraius.database.repositories.lease.LeaseDepositSplitRepository;
import com.notraius.database.repositories.lease.LeaseRepository;
import com.notraius.database.repositories.lease.LeaseTopUpRepository;
import com.notraius.modules.lease.finance.mapper.LeaseFinanceMapper;
import com.notraius.modules.lease.state.service.LeaseStateService;
import org.primarion.module.access.activity.database.repositories.ActivityRepository;
import org.primarion.module.access.audit.AuditLogWriter;

import static org.mockito.Mockito.mock;

/**
 * The merged finance service's fourteen doors. A test hands over the few it
 * drives and leaves the rest mocked: one service means one constructor, and no
 * test should care about the others' collaborators.
 */
final class LeaseFinanceServiceFixture {

    private LeaseBreakdownRepository breakdownRepository = mock(LeaseBreakdownRepository.class);
    private LeaseDepositRepository depositRepository = mock(LeaseDepositRepository.class);
    private LeaseDepositSplitRepository splitRepository = mock(LeaseDepositSplitRepository.class);
    private LeaseRepository leaseRepository = mock(LeaseRepository.class);
    private LeaseTopUpRepository topUpRepository = mock(LeaseTopUpRepository.class);
    private PropertyPolicyValueRepository valueRepository = mock(PropertyPolicyValueRepository.class);
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

    static LeaseFinanceServiceFixture aService() {
        return new LeaseFinanceServiceFixture();
    }

    LeaseFinanceServiceFixture breakdownRepository(LeaseBreakdownRepository value) {
        this.breakdownRepository = value;
        return this;
    }

    LeaseFinanceServiceFixture depositRepository(LeaseDepositRepository value) {
        this.depositRepository = value;
        return this;
    }

    LeaseFinanceServiceFixture splitRepository(LeaseDepositSplitRepository value) {
        this.splitRepository = value;
        return this;
    }

    LeaseFinanceServiceFixture leaseRepository(LeaseRepository value) {
        this.leaseRepository = value;
        return this;
    }

    LeaseFinanceServiceFixture topUpRepository(LeaseTopUpRepository value) {
        this.topUpRepository = value;
        return this;
    }

    LeaseFinanceServiceFixture valueRepository(PropertyPolicyValueRepository value) {
        this.valueRepository = value;
        return this;
    }

    LeaseFinanceServiceFixture payPaymentClient(PayPaymentClient value) {
        this.payPaymentClient = value;
        return this;
    }

    LeaseFinanceServiceFixture assessmentClient(ReputationAssessmentClient value) {
        this.assessmentClient = value;
        return this;
    }

    LeaseFinanceServiceFixture activityRepository(ActivityRepository value) {
        this.activityRepository = value;
        return this;
    }

    LeaseFinanceServiceFixture statusResolver(StatusResolver value) {
        this.statusResolver = value;
        return this;
    }

    LeaseFinanceServiceFixture stateService(LeaseStateService value) {
        this.stateService = value;
        return this;
    }

    LeaseFinanceServiceFixture auditLogWriter(AuditLogWriter value) {
        this.auditLogWriter = value;
        return this;
    }

    LeaseFinanceService build() {
        return new LeaseFinanceService(breakdownRepository, depositRepository, splitRepository,
                leaseRepository, topUpRepository, valueRepository, payPaymentClient,
                assessmentClient, activityRepository, statusResolver, stateService,
                financeMapper, new AuditTrail(objectMapper, auditLogWriter));
    }
}
