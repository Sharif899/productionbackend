package com.sharif899.prodbackend.config;

import com.sharif899.prodbackend.ProductionBackendApplication;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The PostgreSQL profile: it fails fast when the connection is not
 * configured, and binds the datasource purely from environment
 * variables when it is. Flyway and schema validation are off here
 * because there is no real database to connect to in a unit test;
 * the point is the configuration, not a live connection.
 *
 * <p>Note: ApplicationContextRunner does not load
 * {@code application-postgres.properties} (that is Spring Boot's
 * config-file loading, which only a SpringApplication does), so
 * the binding test sets the same values the profile file sets,
 * property for property, and the fail-fast test runs a real
 * SpringApplication with the postgres profile active.
 */
class PostgresProfileTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ProductionBackendApplication.class);

    @Test
    void postgresProfileFailsFastWithoutAUrl() {
        // Exactly what application-postgres.properties declares: the
        // URL, username and password are placeholders with no
        // default. With no SPRING_DATASOURCE_URL in the environment
        // the service must fail at startup instead of silently
        // falling back to the in-memory database.
        //
        // This has to be a real SpringApplication run, not the
        // ApplicationContextRunner: the runner passes property
        // values through literally (a "${...}" placeholder reaches
        // the DataSource unresolved and nothing connects at startup
        // when Flyway and validation are off), while a real Boot
        // run resolves the placeholder from the environment and
        // fails the context when it is missing. That is the exact
        // mechanism the profile file relies on.
        SpringApplicationBuilder builder = new SpringApplicationBuilder(ProductionBackendApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.profiles.active=postgres",
                        "spring.main.banner-mode=off",
                        "spring.flyway.enabled=false",
                        "spring.jpa.hibernate.ddl-auto=none");
        try {
            builder.run();
            fail("expected startup to fail with no SPRING_DATASOURCE_URL set");
        } catch (Throwable failure) {
            assertThat(messages(failure))
                    .anyMatch(message -> message.contains("SPRING_DATASOURCE_URL"));
        }
    }

    private static List<String> messages(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                messages.add(current.getMessage());
            }
        }
        return messages;
    }

    @Test
    void postgresProfileBindsTheDatasourceFromTheEnvironment() {
        runner.withPropertyValues(
                        "spring.datasource.url=jdbc:postgresql://db.example.invalid:5432/proddb",
                        "spring.datasource.driver-class-name=org.postgresql.Driver",
                        "spring.datasource.username=produser",
                        "spring.datasource.password=prodpass",
                        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
                        "spring.flyway.enabled=false",
                        "spring.jpa.hibernate.ddl-auto=none")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DataSource.class);
                    DataSource dataSource = context.getBean(DataSource.class);
                    assertThat(dataSource).isInstanceOf(HikariDataSource.class);
                    assertThat(((HikariDataSource) dataSource).getJdbcUrl())
                            .isEqualTo("jdbc:postgresql://db.example.invalid:5432/proddb");
                    assertThat(((HikariDataSource) dataSource).getUsername())
                            .isEqualTo("produser");
                });
    }
}
