package com.notraius;

import com.notraius.config.notraius.properties.InterServiceProperties;
import com.notraius.config.notraius.properties.NotraiusAppProperties;
import com.notraius.config.notraius.properties.NotraiusProperties;
import org.primarion.module.configs.configs.async.DotenvConfig;
import org.primarion.module.configs.configs.scheduler.CommonModule;
import org.primarion.module.configs.configs.redis.RedisModule;
import org.primarion.module.configs.configs.scheduler.StorageModule;
import org.primarion.module.app.security.ratelimit.auth.actor.config.SecurityAuthModule;
import org.primarion.module.app.security.ratelimit.ratelimit.redis.SecurityRateLimitModule;
import org.primarion.module.app.security.ratelimit.crypto.key.CryptoModule;
import org.primarion.module.utils.utils.security.UtilsModule;
import org.primarion.module.utils.utils.security.security.users.user.UsersModule;
import org.primarion.module.utils.utils.security.ServicesModule;
import org.primarion.database.DatabaseModule;
import org.primarion.module.communication.communication.communication.CommunicationModule;
import org.primarion.module.app.app.app.config.IdentityAppsModule;
import org.primarion.module.app.access.external.AccessModule;
import org.primarion.module.app.security.preferences.identity.IdentityPrefsModule;
import org.primarion.module.app.security.ratelimit.token.IdentityTokenModule;
import org.primarion.module.communication.communication.chat.chat.ChatModule;
import org.primarion.module.communication.infra.tracing.lookups.LookupModule;
import org.primarion.module.communication.infra.anomaly.flowmap.config.FlowmapModule;
import org.primarion.module.configs.event.event.EventModule;
import org.primarion.module.app.engagement.engagement.EngagementModule;
import org.primarion.module.communication.marketplace.card.MarketplaceModule;
import org.primarion.module.app.subscription.subscription.SubscriptionModule;
import org.primarion.module.communication.infra.anomaly.webhook.core.WebhookModule;
import org.primarion.module.utils.flow.wiring.FlowModule;
import org.primarion.module.communication.infra.anomaly.anomaly.AnomalyModule;
import org.primarion.module.communication.infra.ops.idempotency.IdempotencyModule;
import org.primarion.module.communication.infra.tracing.tracing.TracingModule;
import org.primarion.module.communication.infra.ops.export.encoders.ExportModule;
import org.primarion.module.communication.infra.anomaly.partition.LogPartitionModule;
import org.primarion.module.communication.infra.ops.OpsModule;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

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
// The visit sweeps (PLAN 5.1's fences) are clock-driven: silence never keeps money.
@EnableScheduling
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
    MarketplaceModule.class,
    EngagementModule.class,
    WebhookModule.class,
    FlowModule.class,
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
