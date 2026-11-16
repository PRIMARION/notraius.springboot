package com.notraius.modules.visit.visits.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.common.audit.AuditTrail;
import com.notraius.common.integration.PayPaymentClient;
import com.notraius.common.integration.ReputationAssessmentClient;
import com.notraius.common.services.StatusResolver;
import com.notraius.common.services.VisitLedgerService;
import com.notraius.common.services.VisitTurnReader;
import com.notraius.config.properties.NotraiusProperties;
import com.notraius.database.repositories.LogsRepository;
import com.notraius.database.repositories.reference.ValidityRepository;
import com.notraius.database.repositories.visit.VisitPricingRepository;
import com.notraius.database.repositories.visit.VisitRepository;
import com.notraius.modules.core.common.schedule.SweepPager;
import com.notraius.modules.core.common.schedule.SweepTestSupport;
import com.notraius.modules.visit.visits.mapper.VisitMapper;
import org.primarion.module.access.activity.database.repositories.ActivityRepository;
import org.primarion.module.access.audit.AuditLogWriter;
import org.primarion.module.communication.notification.service.NotificationService;
import org.primarion.module.infra.webhook.service.WebhookDispatchHelper;
import org.primarion.module.utils.security.users.UserClient;

/**
 * Builds the one visits service with only the collaborators the test under
 * examination drives; every other dependency stays null.
 */
final class VisitServiceFixture {

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

    VisitServiceFixture webhookDispatchHelper(WebhookDispatchHelper value) {
        this.webhookDispatchHelper = value;
        return this;
    }

    VisitServiceFixture notificationService(NotificationService value) {
        this.notificationService = value;
        return this;
    }

    VisitServiceFixture visitRepository(VisitRepository value) {
        this.visitRepository = value;
        return this;
    }

    VisitServiceFixture payPaymentClient(PayPaymentClient value) {
        this.payPaymentClient = value;
        return this;
    }

    VisitServiceFixture statusResolver(StatusResolver value) {
        this.statusResolver = value;
        return this;
    }

    VisitServiceFixture visitMapper(VisitMapper value) {
        this.visitMapper = value;
        return this;
    }

    VisitServiceFixture userClient(UserClient value) {
        this.userClient = value;
        return this;
    }

    VisitServiceFixture auditLogWriter(AuditLogWriter value) {
        this.auditLogWriter = value;
        return this;
    }

    VisitServiceFixture objectMapper(ObjectMapper value) {
        this.objectMapper = value;
        return this;
    }

    VisitServiceFixture assessmentClient(ReputationAssessmentClient value) {
        this.assessmentClient = value;
        return this;
    }

    VisitServiceFixture activityRepository(ActivityRepository value) {
        this.activityRepository = value;
        return this;
    }

    VisitServiceFixture properties(NotraiusProperties value) {
        this.properties = value;
        return this;
    }

    VisitServiceFixture visitLedgerService(VisitLedgerService value) {
        this.visitLedgerService = value;
        return this;
    }

    VisitServiceFixture visitTurnReader(VisitTurnReader value) {
        this.visitTurnReader = value;
        return this;
    }

    VisitServiceFixture visitPricingRepository(VisitPricingRepository value) {
        this.visitPricingRepository = value;
        return this;
    }

    VisitServiceFixture validityRepository(ValidityRepository value) {
        this.validityRepository = value;
        return this;
    }

    VisitServiceFixture logsRepository(LogsRepository value) {
        this.logsRepository = value;
        return this;
    }

    VisitServiceFixture sweeps(SweepPager value) {
        this.sweeps = value;
        return this;
    }

    VisitService build() {
        SweepPager pager = sweeps != null ? sweeps : SweepTestSupport.pager(properties);
        return new VisitService(visitRepository, payPaymentClient, statusResolver, visitMapper, userClient,
                new AuditTrail(objectMapper, auditLogWriter), assessmentClient, activityRepository, properties,
                visitLedgerService, visitTurnReader, visitPricingRepository, validityRepository, logsRepository,
                pager, webhookDispatchHelper, notificationService, objectMapper);
    }
}
