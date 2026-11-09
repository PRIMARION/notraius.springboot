package com.notraius.config.web;

import org.primarion.module.infra.tracing.RequestTrace;
import org.primarion.module.security.ratelimit.service.RateLimitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The IP throttle for the anonymous read tier (UPGRADE.md D1/D2).
 * <p>
 * A public handler carries <b>no</b> {@code @PreAuthorize}: with no session
 * there is no identifiable principal, so the {@code @authz} chain — role
 * gate, permission rows, plan limits, rate limits — has nothing to resolve
 * and must not run at all (an annotation there either denies the anonymous
 * caller outright or requires an authority that does not exist). The door is
 * therefore the servlet chain's {@code PUBLIC_GET_ENDPOINTS} entry alone.
 * <p>
 * What an annotation used to give these reads for free was layer 5, the
 * rate limiter. This bean puts that one layer back, IP-scoped, because the
 * caller has no identity: the activity code is the same
 * {@code FAMILY.OPERATION} pair the annotation named, the subject is the
 * trusted-proxy-aware client IP, and enforcement stays admin-managed in
 * {@code rate.limits} — no row, no limit (the platform default), a row
 * turns the limit on with no code change.
 * <p>
 * Failures are the limiter's own: an unenforceable check raises 429 rather
 * than granting a free pass (same posture as the public authenticity
 * probes). Calls with no client IP on the thread (unit tests, background
 * work) are skipped — there is no subject to charge.
 */
@Component
public class PublicReadThrottle {

    private static final Logger log = LoggerFactory.getLogger(PublicReadThrottle.class);

    private static final String MESSAGE = "Too many public read requests. Try again later.";

    private final RateLimitService rateLimitService;

    public PublicReadThrottle(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    /**
     * Charges one IP-scoped hit for {@code activityCode} on the current
     * request. Codes are {@code FAMILY.OPERATION} (the limiter splits them
     * into an activity row and an operation; a bare family is malformed and
     * skipped).
     */
    public void charge(String activityCode) {
        String clientIp = RequestTrace.getClientIp();
        if (clientIp == null || clientIp.isBlank()) {
            log.debug("Public read throttle skipped for [{}] — no client IP on this thread", activityCode);
            return;
        }
        rateLimitService.checkIpRateLimit(activityCode, clientIp, MESSAGE);
    }
}
