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
    // #PROBLEMS-B3 (flowmap): the dev-only source/trace explorer must never
    // boot in production, even if someone ships FLOWMAP_ENABLED=true in .env.
    private final boolean flowmapEnabled;

    public ProductionStartupGuard(
            @Value("${app.profile:dev}") String profile,
            @Value("${app.security.hmac-key:}") String hmacKey,
            @Value("${app.security.encryption-key:}") String encryptionKey,
            @Value("${spring.datasource.password:}") String dbPassword,
            @Value("${app.shared.datasource.password:}") String sharedDbPass,
            @Value("${spring.data.redis.password:}") String redisPassword,
            @Value("${app.flowmap.enabled:false}") boolean flowmapEnabled) {
        this.prodProfile = isProductionProfile(profile);
        this.hmacKey = hmacKey;
        this.encryptionKey = encryptionKey;
        this.dbPassword = dbPassword;
        this.sharedDbPass = sharedDbPass;
        this.redisPassword = redisPassword;
        this.flowmapEnabled = flowmapEnabled;
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
        // #PROBLEMS-B3 (flowmap): source snippets + endpoint traces are a
        // dev instrument — hard-fail the boot when enabled in production.
        if (flowmapEnabled) violations.add("FLOWMAP_ENABLED must be false in production (dev-only source explorer)");
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
