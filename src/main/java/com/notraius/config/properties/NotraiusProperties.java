package com.notraius.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Notraius microservice runtime configuration.
 * <p>
 * Identity lives in the primarion microservice, money movement in pay —
 * notraius resolves all of them over HTTP through the configured base
 * URLs and never holds foreign tables. The domain grows here as it
 * materializes; only {@code notificationsEnabled} exists today (the
 * shared communication module reads it).
 */
@ConfigurationProperties(prefix = "app.notraius")
@Getter
@Setter
public class NotraiusProperties {

    /**
     * Whether in-app notifications are written to {@code notraius.notifications}.
     */
    private boolean notificationsEnabled = true;

    /**
     * Platform code this microservice's reference data is scoped to.
     */
    private String platformCode = "NOTRAIUS";

    /**
     * Base URL of the pay microservice, used for service-to-service currency,
     * wallet and payment operations.
     */
    private String payBaseUrl;

    /** Platform target identifiers — never configurable: static constants. */
    private static final String PLATFORM_TARGET_PRIMARION = "PRIMARION";
    private static final String PLATFORM_TARGET_PAY = "PAY";

    public String getPlatformTargetPrimarion() { return PLATFORM_TARGET_PRIMARION; }
    public String getPlatformTargetPay() { return PLATFORM_TARGET_PAY; }
}
