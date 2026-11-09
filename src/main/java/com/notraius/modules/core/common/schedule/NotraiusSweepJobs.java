package com.notraius.modules.core.common.schedule;

import com.notraius.modules.lease.agreement.service.LeaseAgreementService;
import com.notraius.modules.lease.state.service.LeaseStateService;
import com.notraius.modules.visit.visits.service.VisitService;
import lombok.extern.slf4j.Slf4j;
import org.primarion.module.infra.ops.SimpleMutex;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The four clocks of Parts 5 and 6, and the one law that keeps them from
 * stepping on each other.
 * <p>
 * <b>One door, four locks.</b> Each tick holds a {@link SimpleMutex} around its
 * whole pass — a Redis {@code SET NX PX}, fail-open, TTL-bounded — so two
 * instances never sweep the same worklist at once. The mutex is the safety net,
 * not the primary guard: every job is also a {@code fixedDelay}, so a pass that
 * outlives its interval never overlaps its own successor on the same instance.
 * <p>
 * <b>The mutex and the ledger answer different questions.</b> The mutex asks
 * "may I run now?" across instances; the {@link SweepLedger} cursor asks "where
 * do I continue?" after a budgeted pass stopped mid-worklist. Together they mean
 * a ten-million-row worklist is worked once, steadily, and never from the head
 * every tick.
 * <p>
 * <b>The service owns the law; this class owns the clock.</b> Each method here
 * is a wrapper that acquires, calls the service's pass, logs a failure instead
 * of throwing it into the scheduler's thread, and always releases — so the same
 * pass can still be run by an operator or a test with no scheduler involved.
 */
@Component
@Slf4j
public class NotraiusSweepJobs {

    private final LeaseAgreementService leaseAgreementService;
    private final LeaseStateService leaseStateService;
    private final VisitService visitService;

    private final SimpleMutex leaseExpiryMutex;
    private final SimpleMutex leaseVigilMutex;
    private final SimpleMutex visitExpiryMutex;
    private final SimpleMutex visitOutcomeMutex;

    public NotraiusSweepJobs(LeaseAgreementService leaseAgreementService,
                             LeaseStateService leaseStateService,
                             VisitService visitService,
                             StringRedisTemplate redis,
                             @Value("${app.scheduled.mutex-ttl-minutes:15}") int mutexTtlMinutes) {
        this.leaseAgreementService = leaseAgreementService;
        this.leaseStateService = leaseStateService;
        this.visitService = visitService;
        Duration ttl = Duration.ofMinutes(mutexTtlMinutes);
        this.leaseExpiryMutex = new SimpleMutex(redis, "notraius:lease-expiry", ttl);
        this.leaseVigilMutex = new SimpleMutex(redis, "notraius:lease-vigil", ttl);
        this.visitExpiryMutex = new SimpleMutex(redis, "notraius:visit-expiry", ttl);
        this.visitOutcomeMutex = new SimpleMutex(redis, "notraius:visit-outcome", ttl);
    }

    /** 6.1: silence never keeps money — PENDING leases past the staleness window. */
    @Scheduled(fixedDelayString = "${app.notraius.lease-sweep-delay-ms:900000}",
            initialDelayString = "${app.notraius.lease-sweep-initial-delay-ms:60000}")
    public void runLeaseSweepJob() {
        run(leaseExpiryMutex, "lease sweep", leaseAgreementService::sweep);
    }

    /** 6.2-6.6: the vigil's six worklists. */
    @Scheduled(fixedDelayString = "${app.notraius.lease-vigil-delay-ms:3600000}",
            initialDelayString = "${app.notraius.lease-vigil-initial-delay-ms:120000}")
    public void runVigilTick() {
        run(leaseVigilMutex, "lease vigil", () -> {
            leaseStateService.tick();
            return 0;
        });
    }

    /** 5.1-5.2: the 24-hour and 72-hour fences. */
    @Scheduled(fixedDelayString = "${app.notraius.visit-sweep-delay-ms:900000}",
            initialDelayString = "${app.notraius.visit-sweep-initial-delay-ms:60000}")
    public void runExpirySweepJob() {
        run(visitExpiryMutex, "visit expiry sweep", visitService::expirySweep);
    }

    /** 5.3: the no-show, silent-visit and assessment-hour clocks. */
    @Scheduled(fixedDelayString = "${app.notraius.visit-outcome-sweep-delay-ms:300000}",
            initialDelayString = "${app.notraius.visit-outcome-sweep-initial-delay-ms:120000}")
    public void runOutcomeSweepJob() {
        run(visitOutcomeMutex, "visit outcome sweep", visitService::outcomeSweep);
    }

    /**
     * One tick under one lock: skip when another instance holds it, log — never
     * throw — when the pass fails, and release only after the last row's
     * transaction committed (releasing earlier would let the next instance read
     * rows this pass has not committed yet).
     */
    private void run(SimpleMutex mutex, String name, java.util.function.IntSupplier pass) {
        if (!mutex.tryLock()) {
            log.debug("{} already running elsewhere; skipping this tick", name);
            return;
        }
        try {
            pass.getAsInt();
        } catch (RuntimeException e) {
            log.warn("{} failed: {}", name, e.getMessage());
        } finally {
            mutex.unlock();
        }
    }
}
