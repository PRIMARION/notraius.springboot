package com.notraius.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.primarion.module.app.config.AppProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Notraius-extended {@link AppProperties}.
 * <p>
 * The shared library only knows {@code name}/{@code baseUrl}/{@code frontendUrl}/
 * {@code profile}. The notraius domain reads nothing beyond those today, so
 * this subclass exists purely to keep {@code app.*} configuration available to
 * the shared auto-configurations untouched.
 */
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class NotraiusAppProperties extends AppProperties {
}