package com.farmeraccounts.support;

import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Real PostgreSQL (embedded binaries, no Docker) plus a controllable clock for integration tests. */
@TestConfiguration(proxyBeanMethods = false)
public class TestInfrastructure {

    private static EmbeddedPostgres postgres;

    static synchronized EmbeddedPostgres postgres() {
        if (postgres == null) {
            try {
                postgres = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return postgres;
    }

    @Bean
    @Primary
    public DataSource dataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(postgres().getJdbcUrl("postgres", "postgres"));
        ds.setUsername("postgres");
        ds.setMaximumPoolSize(10);
        return ds;
    }

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock();
    }
}
