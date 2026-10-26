package com.notraius.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import org.slf4j.LoggerFactory;

/**
 * Migrates the notraius database schema from {@code classpath:db/migration} before
 * the application starts. The notraius database holds all rows owned by the notraius
 * microservice (see the ownership rule in the notraius README).
 */
@Configuration
public class NotraiusFlywayConfig {

    @Bean(name = "notraiusFlyway")
    public Flyway notraiusFlyway(DataSource dataSource,
                                  @Value("${spring.flyway.validate-on-migrate:true}") boolean validateOnMigrate) {
        // Custom bean: Spring Boot's property is not applied automatically.
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .validateOnMigrate(validateOnMigrate)
                .load();
        // #CTR-77: Repair before migrate to handle failed migrations.
        try {
            flyway.repair();
            LoggerFactory.getLogger(NotraiusFlywayConfig.class)
                    .info("Flyway repair completed for notraius database");
        } catch (Exception e) {
            LoggerFactory.getLogger(NotraiusFlywayConfig.class)
                    .warn("Flyway repair failed (may be first run): {}", e.getMessage());
        }
        return flyway;
    }

    @Bean(name = "flywayInitializer")
    public FlywayMigrationInitializer flywayInitializer(
            @Qualifier("notraiusFlyway") Flyway notraiusFlyway) {
        return new FlywayMigrationInitializer(notraiusFlyway);
    }
}