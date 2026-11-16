package com.notraius.modules.lease.agreement.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.integration.PayPaymentClient;
import com.notraius.common.services.PolicyResolver;
import com.notraius.common.services.StatusResolver;
import com.notraius.config.properties.NotraiusProperties;
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
import com.notraius.modules.core.common.schedule.SweepPager;
import com.notraius.modules.core.common.schedule.SweepTestSupport;
import com.notraius.modules.lease.agreement.mapper.LeaseConditionMapper;
import com.notraius.modules.lease.agreement.mapper.LeaseMapper;
import com.notraius.modules.lease.state.service.LeaseStateService;
import org.primarion.module.access.audit.AuditLogWriter;
import org.springframework.transaction.PlatformTransactionManager;

import static org.mockito.Mockito.mock;
import org.primarion.module.utils.security.users.UserService;

/**
 * Builds the sub-module's one service for a test: only the collaborators a test
 * actually exercises are set, and the pure ones (mappers, the JSON writer, the
 * configuration) carry real defaults so a test never has to care about them.
 */
final class LeaseAgreementServiceFixture {

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

    static LeaseAgreementServiceFixture aService() {
        return new LeaseAgreementServiceFixture();
    }

    LeaseAgreementServiceFixture pricingRepository(PricingRepository value) {
        this.pricingRepository = value;
        return this;
    }

    LeaseAgreementServiceFixture propertyRepository(PropertyRepository value) {
        this.propertyRepository = value;
        return this;
    }

    LeaseAgreementServiceFixture leaseRepository(LeaseRepository value) {
        this.leaseRepository = value;
        return this;
    }

    LeaseAgreementServiceFixture depositRepository(LeaseDepositRepository value) {
        this.depositRepository = value;
        return this;
    }

    LeaseAgreementServiceFixture splitRepository(LeaseDepositSplitRepository value) {
        this.splitRepository = value;
        return this;
    }

    LeaseAgreementServiceFixture validityRepository(ValidityRepository value) {
        this.validityRepository = value;
        return this;
    }

    LeaseAgreementServiceFixture breakdownRepository(LeaseBreakdownRepository value) {
        this.breakdownRepository = value;
        return this;
    }

    LeaseAgreementServiceFixture noticeRepository(LeaseNoticeRepository value) {
        this.noticeRepository = value;
        return this;
    }

    LeaseAgreementServiceFixture conditionRepository(LeaseConditionRepository value) {
        this.conditionRepository = value;
        return this;
    }

    LeaseAgreementServiceFixture settlementRepository(LeaseSettlementRepository value) {
        this.settlementRepository = value;
        return this;
    }

    LeaseAgreementServiceFixture valueRepository(PropertyPolicyValueRepository value) {
        this.valueRepository = value;
        return this;
    }

    LeaseAgreementServiceFixture payPaymentClient(PayPaymentClient value) {
        this.payPaymentClient = value;
        return this;
    }

    LeaseAgreementServiceFixture policyResolver(PolicyResolver value) {
        this.policyResolver = value;
        return this;
    }

    LeaseAgreementServiceFixture typeRepository(TypeRepository value) {
        this.typeRepository = value;
        return this;
    }

    LeaseAgreementServiceFixture statusResolver(StatusResolver value) {
        this.statusResolver = value;
        return this;
    }

    LeaseAgreementServiceFixture userService(UserService value) {
        this.userService = value;
        return this;
    }

    LeaseAgreementServiceFixture properties(NotraiusProperties value) {
        this.properties = value;
        return this;
    }

    LeaseAgreementServiceFixture stateService(LeaseStateService value) {
        this.stateService = value;
        return this;
    }

    LeaseAgreementServiceFixture auditLogWriter(AuditLogWriter value) {
        this.auditLogWriter = value;
        return this;
    }

    LeaseAgreementServiceFixture sweeps(SweepPager value) {
        this.sweeps = value;
        return this;
    }

    LeaseAgreementService build() {
        SweepPager pager = sweeps != null ? sweeps : SweepTestSupport.pager(properties);
        return new LeaseAgreementService(pricingRepository, propertyRepository, leaseRepository,
                depositRepository, splitRepository, validityRepository, breakdownRepository,
                noticeRepository, conditionRepository, settlementRepository, valueRepository,
                payPaymentClient, policyResolver, typeRepository, statusResolver, userService,
                properties, stateService, leaseMapper, conditionMapper, new AuditTrail(objectMapper, auditLogWriter),
                transactionManager, pager, new com.notraius.common.services.LeaseAnnouncer(null, null, objectMapper));
    }
}
