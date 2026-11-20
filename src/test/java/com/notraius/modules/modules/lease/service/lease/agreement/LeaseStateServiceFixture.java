package com.notraius.modules.modules.lease.service.lease.agreement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.integration.pay.PayPaymentClient;
import com.notraius.common.services.logs.StatusResolver;
import com.notraius.config.notraius.properties.NotraiusProperties;
import com.notraius.database.repositories.asset.asset.PropertyRepository;
import com.notraius.database.repositories.lease.lease.records.LeaseBreakdownRepository;
import com.notraius.database.repositories.lease.lease.records.LeaseConditionRepository;
import com.notraius.database.repositories.lease.lease.lease.LeaseDepositRepository;
import com.notraius.database.repositories.lease.lease.lease.LeaseDepositSplitRepository;
import com.notraius.database.repositories.lease.lease.records.LeaseNoticeRepository;
import com.notraius.database.repositories.lease.lease.lease.LeaseRepairRepository;
import com.notraius.database.repositories.lease.lease.lease.LeaseRepository;
import com.notraius.database.repositories.lease.lease.lease.LeaseSettlementRepository;
import com.notraius.database.repositories.lease.reference.duration.TypeRepository;
import com.notraius.modules.core.core.common.schedule.sweep.SweepPager;
import com.notraius.modules.modules.lease.common.schedule.SweepTestSupport;
import com.notraius.modules.lease.lease.agreement.service.LeaseAgreementService;
import com.notraius.modules.lease.lease.finance.LeaseFinanceService;
import com.notraius.modules.lease.lease.state.mapper.LeaseNoticeMapper;
import com.notraius.modules.lease.lease.state.mapper.LeaseStateMapper;
import org.primarion.module.app.access.consent.AuditLogWriter;

import static org.mockito.Mockito.mock;
import com.notraius.common.services.visit.LeaseAnnouncer;
import com.notraius.modules.lease.lease.state.LeaseStateService;

/**
 * The merged state service's nineteen doors. A test hands over the few it drives
 * and leaves the rest mocked: one service means one constructor, and no test
 * should care about the others' collaborators.
 */
public final class LeaseStateServiceFixture {

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

    public static LeaseStateServiceFixture aService() {
        return new LeaseStateServiceFixture();
    }

    public LeaseStateServiceFixture leaseRepository(LeaseRepository value) {
        this.leaseRepository = value;
        return this;
    }

    public LeaseStateServiceFixture noticeRepository(LeaseNoticeRepository value) {
        this.noticeRepository = value;
        return this;
    }

    public LeaseStateServiceFixture depositRepository(LeaseDepositRepository value) {
        this.depositRepository = value;
        return this;
    }

    public LeaseStateServiceFixture settlementRepository(LeaseSettlementRepository value) {
        this.settlementRepository = value;
        return this;
    }

    public LeaseStateServiceFixture breakdownRepository(LeaseBreakdownRepository value) {
        this.breakdownRepository = value;
        return this;
    }

    public LeaseStateServiceFixture splitRepository(LeaseDepositSplitRepository value) {
        this.splitRepository = value;
        return this;
    }

    public LeaseStateServiceFixture conditionRepository(LeaseConditionRepository value) {
        this.conditionRepository = value;
        return this;
    }

    public LeaseStateServiceFixture repairRepository(LeaseRepairRepository value) {
        this.repairRepository = value;
        return this;
    }

    public LeaseStateServiceFixture propertyRepository(PropertyRepository value) {
        this.propertyRepository = value;
        return this;
    }

    public LeaseStateServiceFixture typeRepository(TypeRepository value) {
        this.typeRepository = value;
        return this;
    }

    public LeaseStateServiceFixture statusResolver(StatusResolver value) {
        this.statusResolver = value;
        return this;
    }

    public LeaseStateServiceFixture payPaymentClient(PayPaymentClient value) {
        this.payPaymentClient = value;
        return this;
    }

    public LeaseStateServiceFixture agreementService(LeaseAgreementService value) {
        this.agreementService = value;
        return this;
    }

    public LeaseStateServiceFixture financeService(LeaseFinanceService value) {
        this.financeService = value;
        return this;
    }

    public LeaseStateServiceFixture properties(NotraiusProperties value) {
        this.properties = value;
        return this;
    }

    public LeaseStateServiceFixture auditLogWriter(AuditLogWriter value) {
        this.auditLogWriter = value;
        return this;
    }

    public LeaseStateServiceFixture sweeps(SweepPager value) {
        this.sweeps = value;
        return this;
    }

    public LeaseStateService build() {
        SweepPager pager = sweeps != null ? sweeps : SweepTestSupport.pager(properties);
        return new LeaseStateService(leaseRepository, noticeRepository, depositRepository,
                settlementRepository, breakdownRepository, splitRepository, conditionRepository,
                repairRepository, propertyRepository, typeRepository, statusResolver,
                payPaymentClient, agreementService, financeService, properties,
                noticeMapper, stateMapper, new AuditTrail(objectMapper, auditLogWriter), pager,
                new com.notraius.common.services.visit.LeaseAnnouncer(null, null, objectMapper));
    }
}
