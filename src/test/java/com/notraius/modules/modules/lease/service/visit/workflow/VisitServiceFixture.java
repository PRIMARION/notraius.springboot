package com.notraius.modules.modules.lease.service.visit.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.integration.pay.PayPaymentClient;
import com.notraius.common.integration.pay.ReputationAssessmentClient;
import com.notraius.common.services.logs.StatusResolver;
import com.notraius.common.services.visit.VisitLedgerService;
import com.notraius.common.services.visit.VisitTurnReader;
import com.notraius.config.notraius.properties.NotraiusProperties;
import com.notraius.database.repositories.lease.LogsRepository;
import com.notraius.database.repositories.lease.reference.communication.ValidityRepository;
import com.notraius.database.repositories.lease.visit.VisitPricingRepository;
import com.notraius.database.repositories.lease.visit.VisitRepository;
import com.notraius.modules.core.core.common.schedule.sweep.SweepPager;
import com.notraius.modules.modules.lease.common.schedule.SweepTestSupport;
import com.notraius.modules.lease.visit.visits.VisitMapper;
import org.primarion.module.app.access.consent.database.ActivityRepository;
import org.primarion.module.app.access.consent.AuditLogWriter;
import org.primarion.module.communication.communication.communication.notification.service.NotificationService;
import org.primarion.module.communication.infra.anomaly.webhook.service.WebhookDispatchHelper;
import org.primarion.module.utils.utils.security.security.users.user.UserClient;
import com.notraius.modules.lease.visit.visits.service.VisitService;

/**
 * Builds the one visits service with only the collaborators the test under
 * examination drives; every other dependency stays null.
 */
public final class VisitServiceFixture {

    private VisitRepository visitRepository;
    private PayPaymentClient payPaymentClient;
    private StatusResolver statusResolver;
    private VisitMapper visitMapper = new VisitMapper();
    private UserClient userClient;
    private AuditLogWriter auditLogWriter;
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private ReputationAssessmentClient assessmentClient;
    private ActivityRepository activityRepository;
    private NotraiusProperties properties;
    private VisitLedgerService visitLedgerService;
    private VisitTurnReader visitTurnReader;
    private VisitPricingRepository visitPricingRepository;
    private ValidityRepository validityRepository;
    private LogsRepository logsRepository;
    private SweepPager sweeps;
    private WebhookDispatchHelper webhookDispatchHelper;
    private NotificationService notificationService;

    public VisitServiceFixture webhookDispatchHelper(WebhookDispatchHelper value) {
        this.webhookDispatchHelper = value;
        return this;
    }

    public VisitServiceFixture notificationService(NotificationService value) {
        this.notificationService = value;
        return this;
    }

    public VisitServiceFixture visitRepository(VisitRepository value) {
        this.visitRepository = value;
        return this;
    }

    public VisitServiceFixture payPaymentClient(PayPaymentClient value) {
        this.payPaymentClient = value;
        return this;
    }

    public VisitServiceFixture statusResolver(StatusResolver value) {
        this.statusResolver = value;
        return this;
    }

    public VisitServiceFixture visitMapper(VisitMapper value) {
        this.visitMapper = value;
        return this;
    }

    public VisitServiceFixture userClient(UserClient value) {
        this.userClient = value;
        return this;
    }

    public VisitServiceFixture auditLogWriter(AuditLogWriter value) {
        this.auditLogWriter = value;
        return this;
    }

    public VisitServiceFixture objectMapper(ObjectMapper value) {
        this.objectMapper = value;
        return this;
    }

    public VisitServiceFixture assessmentClient(ReputationAssessmentClient value) {
        this.assessmentClient = value;
        return this;
    }

    public VisitServiceFixture activityRepository(ActivityRepository value) {
        this.activityRepository = value;
        return this;
    }

    public VisitServiceFixture properties(NotraiusProperties value) {
        this.properties = value;
        return this;
    }

    public VisitServiceFixture visitLedgerService(VisitLedgerService value) {
        this.visitLedgerService = value;
        return this;
    }

    public VisitServiceFixture visitTurnReader(VisitTurnReader value) {
        this.visitTurnReader = value;
        return this;
    }

    public VisitServiceFixture visitPricingRepository(VisitPricingRepository value) {
        this.visitPricingRepository = value;
        return this;
    }

    public VisitServiceFixture validityRepository(ValidityRepository value) {
        this.validityRepository = value;
        return this;
    }

    public VisitServiceFixture logsRepository(LogsRepository value) {
        this.logsRepository = value;
        return this;
    }

    public VisitServiceFixture sweeps(SweepPager value) {
        this.sweeps = value;
        return this;
    }

    public VisitService build() {
        SweepPager pager = sweeps != null ? sweeps : SweepTestSupport.pager(properties);
        return new VisitService(visitRepository, payPaymentClient, statusResolver, visitMapper, userClient,
                new AuditTrail(objectMapper, auditLogWriter), assessmentClient, activityRepository, properties,
                visitLedgerService, visitTurnReader, visitPricingRepository, validityRepository, logsRepository,
                pager, webhookDispatchHelper, notificationService, objectMapper);
    }
}
