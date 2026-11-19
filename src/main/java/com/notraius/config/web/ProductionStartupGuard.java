package com.notraius.config.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Production startup gate: refuses to boot with development-grade
 * configuration. Every check is a hard failure when {@code APP_PROFILE=prod}.
 */
@Component
public class ProductionStartupGuard implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductionStartupGuard.class);

    private final boolean prodProfile;
    private final String hmacKey;
    private final String encryptionKey;
    private final String dbPassword;
    private final String sharedDbPass;
    private final String redisPassword;
    // The shared partition manager can DROP aged audit months. Its cold-tier
    // export does not exist yet (shared README, decision D1), so retention is
    // OFF everywhere until it does — and a notarius that turned it on would
    // destroy the very chain every ?version= door reads.
    private final int logsRetentionMonths;

    public ProductionStartupGuard(
            @Value("${app.profile:dev}") String profile,
            @Value("${app.security.hmac-key:}") String hmacKey,
            @Value("${app.security.encryption-key:}") String encryptionKey,
            @Value("${spring.datasource.password:}") String dbPassword,
            @Value("${app.shared.datasource.password:}") String sharedDbPass,
            @Value("${spring.data.redis.password:}") String redisPassword,
            @Value("${app.logs.retention-months:0}") int logsRetentionMonths) {
        this.prodProfile = isProductionProfile(profile);
        this.hmacKey = hmacKey;
        this.encryptionKey = encryptionKey;
        this.dbPassword = dbPassword;
        this.sharedDbPass = sharedDbPass;
        this.redisPassword = redisPassword;
        this.logsRetentionMonths = logsRetentionMonths;
    }

    @Override
    public void run(String... args) {
        if (!prodProfile) {
            return;
        }
        List<String> violations = new ArrayList<>();
        // #AUTH-CENTRAL: keys are PER-SERVICE — HMAC_KEY is still required,
        // but it must be THIS service's OWN unique key (never shared across
        // services); platform tokens are verified via primarion introspection.
        if (hmacKey == null || hmacKey.isBlank()) violations.add("HMAC_KEY must be set (this service's own unique key)");
        if (encryptionKey == null || encryptionKey.isBlank()) violations.add("ENCRYPTION_KEY must be set");
        if (dbPassword == null || dbPassword.isBlank()) violations.add("DB_PASS must be set");
        if (sharedDbPass == null || sharedDbPass.isBlank()) violations.add("SHARED_DB_PASS must be set");
        if (redisPassword == null || redisPassword.isBlank()) violations.add("REDIS_PASS must be set");
        // The audit chain is not an operational log here: it is the storage the
        // version doors read (`?version=N`, `/revisions`, `/pricings/answering`).
        // DROPPING old partitions therefore deletes the past those doors promise,
        // and shared's retention feature has no cold-tier export yet (D1). It is
        // refused at the only place that can refuse it before data is lost.
        if (logsRetentionMonths > 0) violations.add(
                "APP_LOGS_RETENTION_MONTHS must be 0 in production — no cold-tier audit export exists yet "
                        + "(shared decision D1), and pruning partitions destroys the version chain");
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Production configuration rejected: "
                    + String.join("; ", violations));
        }
        log.info("Production startup guard passed");
    }

    private static boolean isProductionProfile(String profile) {
        if (profile == null || profile.isBlank()) return false;
        String lower = profile.trim().toLowerCase(Locale.ROOT);
        return "prod".equals(lower) || "production".equals(lower) || "prd".equals(lower);
    }
}
