package com.notraius.modules.lease.state.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.integration.PayPaymentClient;
import com.notraius.common.services.StatusResolver;
import com.notraius.config.properties.NotraiusProperties;
import com.notraius.database.repositories.asset.PropertyRepository;
import com.notraius.database.repositories.lease.LeaseBreakdownRepository;
import com.notraius.database.repositories.lease.LeaseConditionRepository;
import com.notraius.database.repositories.lease.LeaseDepositRepository;
import com.notraius.database.repositories.lease.LeaseDepositSplitRepository;
import com.notraius.database.repositories.lease.LeaseNoticeRepository;
import com.notraius.database.repositories.lease.LeaseRepairRepository;
import com.notraius.database.repositories.lease.LeaseRepository;
import com.notraius.database.repositories.lease.LeaseSettlementRepository;
import com.notraius.database.repositories.reference.TypeRepository;
import com.notraius.modules.core.common.schedule.SweepPager;
import com.notraius.modules.core.common.schedule.SweepTestSupport;
import com.notraius.modules.lease.agreement.service.LeaseAgreementService;
import com.notraius.modules.lease.finance.service.LeaseFinanceService;
import com.notraius.modules.lease.state.mapper.LeaseNoticeMapper;
import com.notraius.modules.lease.state.mapper.LeaseStateMapper;
import org.primarion.module.access.audit.AuditLogWriter;

import static org.mockito.Mockito.mock;

/**
 * The merged state service's nineteen doors. A test hands over the few it drives
 * and leaves the rest mocked: one service means one constructor, and no test
 * should care about the others' collaborators.
 */
final class LeaseStateServiceFixture {

    private LeaseRepository leaseRepository = mock(LeaseRepository.class);
    private LeaseNoticeRepository noticeRepository = mock(LeaseNoticeRepository.class);
    private LeaseDepositRepository depositRepository = mock(LeaseDepositRepository.class);
    private LeaseSettlementRepository settlementRepository = mock(LeaseSettlementRepository.class);
    private LeaseBreakdownRepository breakdownRepository = mock(LeaseBreakdownRepository.class);
    private LeaseDepositSplitRepository splitRepository = mock(LeaseDepositSplitRepository.class);
    private LeaseConditionRepository conditionRepository = mock(LeaseConditionRepository.class);
    private LeaseRepairRepository repairRepository = mock(LeaseRepairRepository.class);
    private PropertyRepository propertyRepository = mock(PropertyRepository.class);
    private TypeRepository typeRepository = mock(TypeRepository.class);
    private StatusResolver statusResolver = mock(StatusResolver.class);
    private PayPaymentClient payPaymentClient = mock(PayPaymentClient.class);
    private LeaseAgreementService agreementService = mock(LeaseAgreementService.class);
    private LeaseFinanceService financeService = mock(LeaseFinanceService.class);
    private NotraiusProperties properties = new NotraiusProperties();
    private LeaseNoticeMapper noticeMapper = new LeaseNoticeMapper();
    private LeaseStateMapper stateMapper = new LeaseStateMapper();
    private AuditLogWriter auditLogWriter = mock(AuditLogWriter.class);
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private SweepPager sweeps;

    private LeaseStateServiceFixture() {
    }

    static LeaseStateServiceFixture aService() {
        return new LeaseStateServiceFixture();
    }

    LeaseStateServiceFixture leaseRepository(LeaseRepository value) {
        this.leaseRepository = value;
        return this;
    }

    LeaseStateServiceFixture noticeRepository(LeaseNoticeRepository value) {
        this.noticeRepository = value;
        return this;
    }

    LeaseStateServiceFixture depositRepository(LeaseDepositRepository value) {
        this.depositRepository = value;
        return this;
    }

    LeaseStateServiceFixture settlementRepository(LeaseSettlementRepository value) {
        this.settlementRepository = value;
        return this;
    }

    LeaseStateServiceFixture breakdownRepository(LeaseBreakdownRepository value) {
        this.breakdownRepository = value;
        return this;
    }

    LeaseStateServiceFixture splitRepository(LeaseDepositSplitRepository value) {
        this.splitRepository = value;
        return this;
    }

    LeaseStateServiceFixture conditionRepository(LeaseConditionRepository value) {
        this.conditionRepository = value;
        return this;
    }

    LeaseStateServiceFixture repairRepository(LeaseRepairRepository value) {
        this.repairRepository = value;
        return this;
    }

    LeaseStateServiceFixture propertyRepository(PropertyRepository value) {
        this.propertyRepository = value;
        return this;
    }

    LeaseStateServiceFixture typeRepository(TypeRepository value) {
        this.typeRepository = value;
        return this;
    }

    LeaseStateServiceFixture statusResolver(StatusResolver value) {
        this.statusResolver = value;
        return this;
    }

    LeaseStateServiceFixture payPaymentClient(PayPaymentClient value) {
        this.payPaymentClient = value;
        return this;
    }

    LeaseStateServiceFixture agreementService(LeaseAgreementService value) {
        this.agreementService = value;
        return this;
    }

    LeaseStateServiceFixture financeService(LeaseFinanceService value) {
        this.financeService = value;
        return this;
    }

    LeaseStateServiceFixture properties(NotraiusProperties value) {
        this.properties = value;
        return this;
    }

    LeaseStateServiceFixture auditLogWriter(AuditLogWriter value) {
        this.auditLogWriter = value;
        return this;
    }

    LeaseStateServiceFixture sweeps(SweepPager value) {
        this.sweeps = value;
        return this;
    }

    LeaseStateService build() {
        SweepPager pager = sweeps != null ? sweeps : SweepTestSupport.pager(properties);
        return new LeaseStateService(leaseRepository, noticeRepository, depositRepository,
                settlementRepository, breakdownRepository, splitRepository, conditionRepository,
                repairRepository, propertyRepository, typeRepository, statusResolver,
                payPaymentClient, agreementService, financeService, properties,
                noticeMapper, stateMapper, new AuditTrail(objectMapper, auditLogWriter), pager,
                new com.notraius.common.services.LeaseAnnouncer(null, null, objectMapper));
    }
}
