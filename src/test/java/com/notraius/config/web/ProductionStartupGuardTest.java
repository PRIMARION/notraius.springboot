package com.notraius.config.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The production gate: development-grade configuration is refused at boot. */
class ProductionStartupGuardTest {

    @Test
    @DisplayName("production with real secrets and no audit pruning boots")
    void productionBootsOnSafeConfiguration() {
        assertThatCode(() -> guard("prod", 0).run())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("audit retention is refused in production — it would delete the version chain")
    void auditRetentionIsRefusedInProduction() {
        // The cold-tier export shared's D1 decision blocks does not exist, so a
        // partition drop is the only outcome. In notarius that chain is the storage
        // behind ?version=N, /revisions and /pricings/answering.
        assertThatThrownBy(() -> guard("prod", 12).run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_LOGS_RETENTION_MONTHS must be 0")
                .hasMessageContaining("version chain");
    }

    @Test
    @DisplayName("the gate is production-only: a dev profile may keep the knob for local trials")
    void devProfileIsNotGated() {
        assertThatCode(() -> guard("dev", 12).run())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a missing secret still fails the boot before anything else matters")
    void missingSecretIsRefused() {
        ProductionStartupGuard guard = new ProductionStartupGuard("prod", "key", "key", "",
                "shared", "redis", false, 0);

        assertThatThrownBy(guard::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_PASS must be set");
    }

    private ProductionStartupGuard guard(String profile, int retentionMonths) {
        return new ProductionStartupGuard(profile, "hmac", "encryption", "db", "shared",
                "redis", false, retentionMonths);
    }
}
