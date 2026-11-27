package com.sharif899.prodbackend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application starts, Flyway runs both migrations, and the
 * tables exist. If the entity mapping disagrees with the migrated
 * schema, ddl-auto=validate fails the context load here, before any
 * other test runs.
 */
@SpringBootTest
class SmokeTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void contextLoadsAndMigrationsRan() {
        // Flyway quotes its history table name and columns in
        // lowercase, so the query must quote them too: H2 uppercases
        // unquoted identifiers and would look for
        // FLYWAY_SCHEMA_HISTORY / SUCCESS, which do not exist.
        Integer migrations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"success\" = TRUE", Integer.class);
        assertThat(migrations).isGreaterThanOrEqualTo(2);
        // The tables exist and are queryable. Counts are not asserted
        // here: other test classes share this in-memory database and
        // insert their own rows.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class))
                .isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments", Integer.class))
                .isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys", Integer.class))
                .isNotNull();
    }
}
