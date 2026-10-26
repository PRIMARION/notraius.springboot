package com.notraius;

import com.notraius.config.properties.InterServiceProperties;
import com.notraius.config.properties.NotraiusAppProperties;
import com.notraius.config.properties.NotraiusProperties;
import org.primarion.module.configs.DotenvConfig;
import org.primarion.module.configs.CommonModule;
import org.primarion.module.configs.RedisModule;
import org.primarion.module.configs.StorageModule;
import org.primarion.module.security.auth.config.SecurityAuthModule;
import org.primarion.module.security.ratelimit.redis.SecurityRateLimitModule;
import org.primarion.module.security.crypto.key.CryptoModule;
import org.primarion.module.utils.UtilsModule;
import org.primarion.module.utils.security.users.UsersModule;
import org.primarion.module.utils.ServicesModule;
import org.primarion.database.DatabaseModule;
import org.primarion.module.communication.CommunicationModule;
import org.primarion.module.app.config.IdentityAppsModule;
import org.primarion.module.access.AccessModule;
import org.primarion.module.security.preferences.IdentityPrefsModule;
import org.primarion.module.security.token.IdentityTokenModule;
import org.primarion.module.communication.chat.ChatModule;
import org.primarion.module.infra.lookups.LookupModule;
import org.primarion.module.infra.flowmap.config.FlowmapModule;
import org.primarion.module.event.EventModule;
import org.primarion.module.subscription.SubscriptionModule;
import org.primarion.module.infra.webhook.WebhookModule;
import org.primarion.module.infra.anomaly.AnomalyModule;
import org.primarion.module.infra.idempotency.IdempotencyModule;
import org.primarion.module.infra.tracing.TracingModule;
import org.primarion.module.infra.export.encoders.ExportModule;
import org.primarion.module.infra.partition.LogPartitionModule;
import org.primarion.module.infra.OpsModule;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

/**
 * Notraius microservice bootstrap.
 * <p>
 * Scans the host ({@code com.notraius}) plus imports the SHARED modules via
 * {@code @ImportAutoConfiguration}. Everything cross-service (identity,
 * access, communication, subscription, webhooks, lookups…) is provided by
 * the shared library — identical to every estate host; the notraius domain
 * grows in {@code com.notraius.modules}.
 */
@SpringBootApplication(exclude = {FlywayAutoConfiguration.class})
@EnableConfigurationProperties({NotraiusProperties.class, NotraiusAppProperties.class, InterServiceProperties.class})
@ComponentScan(basePackages = {"com.notraius"})
@ImportAutoConfiguration({
    CommonModule.class,
    RedisModule.class,
    StorageModule.class,
    CryptoModule.class,
    SecurityAuthModule.class,
    SecurityRateLimitModule.class,
    UtilsModule.class,
    UsersModule.class,
    ServicesModule.class,
    DatabaseModule.class,
    IdentityAppsModule.class,
    AccessModule.class,
    IdentityPrefsModule.class,
    IdentityTokenModule.class,
    ChatModule.class,
    LookupModule.class,
    FlowmapModule.class,
    CommunicationModule.class,
    EventModule.class,
    SubscriptionModule.class,
    WebhookModule.class,
    AnomalyModule.class,
    IdempotencyModule.class,
    TracingModule.class,
    ExportModule.class,
    OpsModule.class,
    LogPartitionModule.class
})
public class NotraiusApplication {

	public static void main(String[] args) {
		DotenvConfig.load();
		SpringApplication app = new SpringApplication(NotraiusApplication.class);
		String env = System.getenv("APP_PROFILE");
		if (env != null && !env.isBlank()) {
			app.setAdditionalProfiles(env);
		}
		app.run(args);
	}

}
