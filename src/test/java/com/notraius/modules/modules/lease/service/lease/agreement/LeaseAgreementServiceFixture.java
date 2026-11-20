package com.notraius.modules.modules.lease.service.lease.agreement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.integration.pay.PayPaymentClient;
import com.notraius.common.services.policy.PolicyResolver;
import com.notraius.common.services.logs.StatusResolver;
import com.notraius.config.notraius.properties.NotraiusProperties;
import com.notraius.database.repositories.asset.asset.PricingRepository;
import com.notraius.database.repositories.asset.asset.PropertyPolicyValueRepository;
import com.notraius.database.repositories.asset.asset.PropertyRepository;
import com.notraius.database.repositories.lease.lease.records.LeaseBreakdownRepository;
import com.notraius.database.repositories.lease.lease.records.LeaseConditionRepository;
import com.notraius.database.repositories.lease.lease.lease.LeaseDepositRepository;
import com.notraius.database.repositories.lease.lease.lease.LeaseDepositSplitRepository;
import com.notraius.database.repositories.lease.lease.records.LeaseNoticeRepository;
import com.notraius.database.repositories.lease.lease.lease.LeaseRepository;
import com.notraius.database.repositories.lease.lease.lease.LeaseSettlementRepository;
import com.notraius.database.repositories.lease.reference.duration.TypeRepository;
import com.notraius.database.repositories.lease.reference.communication.ValidityRepository;
import com.notraius.modules.core.core.common.schedule.sweep.SweepPager;
import com.notraius.modules.modules.lease.common.schedule.SweepTestSupport;
import com.notraius.modules.lease.lease.agreement.mapper.LeaseConditionMapper;
import com.notraius.modules.lease.lease.agreement.mapper.LeaseMapper;
import com.notraius.modules.lease.lease.state.LeaseStateService;
import org.primarion.module.app.access.consent.AuditLogWriter;
import org.springframework.transaction.PlatformTransactionManager;

import static org.mockito.Mockito.mock;
import org.primarion.module.utils.utils.security.security.users.user.UserService;
import com.notraius.common.services.visit.LeaseAnnouncer;
import com.notraius.modules.lease.lease.agreement.service.LeaseAgreementService;

/**
 * Builds the sub-module's one service for a test: only the collaborators a test
 * actually exercises are set, and the pure ones (mappers, the JSON writer, the
 * configuration) carry real defaults so a test never has to care about them.
 */
public final class LeaseAgreementServiceFixture {

    private PricingRepository pricingRepository;
    private PropertyRepository propertyRepository;
    private LeaseRepository leaseRepository;
    private LeaseDepositRepository depositRepository;
    private LeaseDepositSplitRepository splitRepository;
    private ValidityRepository validityRepository;
    private LeaseBreakdownRepository breakdownRepository;
    private LeaseNoticeRepository noticeRepository;
    private LeaseConditionRepository conditionRepository;
    private LeaseSettlementRepository settlementRepository;
    private PropertyPolicyValueRepository valueRepository;
    private PayPaymentClient payPaymentClient;
    private PolicyResolver policyResolver;
    private TypeRepository typeRepository;
    private StatusResolver statusResolver;
    private UserService userService;
    private NotraiusProperties properties = new NotraiusProperties();
    private LeaseStateService stateService;
    private LeaseMapper leaseMapper = new LeaseMapper();
    private LeaseConditionMapper conditionMapper = new LeaseConditionMapper();
    private AuditLogWriter auditLogWriter;
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private SweepPager sweeps;

    private LeaseAgreementServiceFixture() {
    }

    public static LeaseAgreementServiceFixture aService() {
        return new LeaseAgreementServiceFixture();
    }

    public LeaseAgreementServiceFixture pricingRepository(PricingRepository value) {
        this.pricingRepository = value;
        return this;
    }

    public LeaseAgreementServiceFixture propertyRepository(PropertyRepository value) {
        this.propertyRepository = value;
        return this;
    }

    public LeaseAgreementServiceFixture leaseRepository(LeaseRepository value) {
        this.leaseRepository = value;
        return this;
    }

    public LeaseAgreementServiceFixture depositRepository(LeaseDepositRepository value) {
        this.depositRepository = value;
        return this;
    }

    public LeaseAgreementServiceFixture splitRepository(LeaseDepositSplitRepository value) {
        this.splitRepository = value;
        return this;
    }

    public LeaseAgreementServiceFixture validityRepository(ValidityRepository value) {
        this.validityRepository = value;
        return this;
    }

    public LeaseAgreementServiceFixture breakdownRepository(LeaseBreakdownRepository value) {
        this.breakdownRepository = value;
        return this;
    }

    public LeaseAgreementServiceFixture noticeRepository(LeaseNoticeRepository value) {
        this.noticeRepository = value;
        return this;
    }

    public LeaseAgreementServiceFixture conditionRepository(LeaseConditionRepository value) {
        this.conditionRepository = value;
        return this;
    }

    public LeaseAgreementServiceFixture settlementRepository(LeaseSettlementRepository value) {
        this.settlementRepository = value;
        return this;
    }

    public LeaseAgreementServiceFixture valueRepository(PropertyPolicyValueRepository value) {
        this.valueRepository = value;
        return this;
    }

    public LeaseAgreementServiceFixture payPaymentClient(PayPaymentClient value) {
        this.payPaymentClient = value;
        return this;
    }

    public LeaseAgreementServiceFixture policyResolver(PolicyResolver value) {
        this.policyResolver = value;
        return this;
    }

    public LeaseAgreementServiceFixture typeRepository(TypeRepository value) {
        this.typeRepository = value;
        return this;
    }

    public LeaseAgreementServiceFixture statusResolver(StatusResolver value) {
        this.statusResolver = value;
        return this;
    }

    public LeaseAgreementServiceFixture userService(UserService value) {
        this.userService = value;
        return this;
    }

    public LeaseAgreementServiceFixture properties(NotraiusProperties value) {
        this.properties = value;
        return this;
    }

    public LeaseAgreementServiceFixture stateService(LeaseStateService value) {
        this.stateService = value;
        return this;
    }

    public LeaseAgreementServiceFixture auditLogWriter(AuditLogWriter value) {
        this.auditLogWriter = value;
        return this;
    }

    public LeaseAgreementServiceFixture sweeps(SweepPager value) {
        this.sweeps = value;
        return this;
    }

    public LeaseAgreementService build() {
        SweepPager pager = sweeps != null ? sweeps : SweepTestSupport.pager(properties);
        return new LeaseAgreementService(pricingRepository, propertyRepository, leaseRepository,
                depositRepository, splitRepository, validityRepository, breakdownRepository,
                noticeRepository, conditionRepository, settlementRepository, valueRepository,
                payPaymentClient, policyResolver, typeRepository, statusResolver, userService,
                properties, stateService, leaseMapper, conditionMapper, new AuditTrail(objectMapper, auditLogWriter),
                transactionManager, pager, new com.notraius.common.services.visit.LeaseAnnouncer(null, null, objectMapper));
    }
}
