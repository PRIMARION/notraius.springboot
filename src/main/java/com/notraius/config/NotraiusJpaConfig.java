package com.notraius.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Primary persistence for the notraius microservice.
 * <p>
 * Owns everything that lives in the notraius database: the {@code notraius}
 * schema rows (notraius domain entities + notraius registries) and the shared
 * {@code Attachment} row model. Per the ecosystem ownership rule, attachment
 * rows are stored in the consuming microservice's own database, so the shared
 * attachment entity and repository packages are bound to this (primary) entity
 * manager — not to the shared lookup datasource. Geo reference entities
 * (states, countries) and the {@code Media} catalog live in the shared lookup
 * database and are provided by the shared lookup datasource when
 * {@code app.shared.datasource.url} is configured (see
 * {@code org.primarion.database.autoconfigure.SharedLookupDataConfiguration}).
 */
@Configuration
@EnableJpaRepositories(
        basePackages = {
                "com.notraius.database.repositories",
                "org.primarion.module.infra.storage.database.repositories.attachment",
                "org.primarion.module.communication.feedback.database.repositories",
                "org.primarion.module.communication.notification.database.repositories",
                "org.primarion.module.communication.support.database.repositories",
                "org.primarion.module.communication.chat.database.repositories",
                "org.primarion.module.security.preferences.database.repositories",
                "org.primarion.module.subscription.database.repositories",
                "org.primarion.module.security.ratelimit.database.repositories",
                "org.primarion.module.app.database.repositories",
                "org.primarion.module.security.token.database.repositories",
                "org.primarion.module.access.activity.database.repositories",
                "org.primarion.module.access.consent.database.repositories",
                "org.primarion.module.access.seat.database.repositories",
                "org.primarion.module.infra.webhook.database.repositories",
                "org.primarion.module.event.database.repositories"},
        entityManagerFactoryRef = "entityManagerFactory",
        transactionManagerRef = "transactionManager",
        considerNestedRepositories = true)
public class NotraiusJpaConfig {

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder().build();
    }

    @Bean
    @Primary
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(
            DataSource dataSource,
            EntityManagerFactoryBuilder factoryBuilder) {
        return factoryBuilder.dataSource(dataSource)
                .packages("com.notraius.database.entities",
                        "org.primarion.module.infra.storage.database.entities.attachment",
                        "org.primarion.module.communication.feedback.database.entities",
                        "org.primarion.module.communication.notification.database.entities",
                        "org.primarion.module.communication.support.database.entities",
                        "org.primarion.module.communication.chat.database.entities",
                        "org.primarion.module.security.preferences.database.entities",
                        "org.primarion.module.subscription.database.entities",
                        "org.primarion.module.security.ratelimit.database.entities",
                        "org.primarion.module.app.database.entities",
                        "org.primarion.module.security.token.database.entities",
                        "org.primarion.module.access.activity.database.entities",
                        "org.primarion.module.access.consent.database.entities",
                        "org.primarion.module.access.seat.database.entities",
                        "org.primarion.module.infra.webhook.database.entities",
                        "org.primarion.module.event.database.entities")
                .persistenceUnit("notraius")
                .build();
    }

    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(
            EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}