package com.farmeraccounts;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;

/**
 * Local development launcher: starts an embedded PostgreSQL (data kept in backend/.devdb) and the
 * application with the "dev" profile. Run with {@code ./gradlew bootTestRun}. Not for production.
 */
public final class DevServer {

    private DevServer() {}

    public static void main(String[] args) throws Exception {
        EmbeddedPostgres pg = EmbeddedPostgres.builder()
                .setPort(Integer.parseInt(System.getProperty("devdb.port", "54329")))
                .setDataDirectory(Path.of(".devdb"))
                .setCleanDataDirectory(false)
                .start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                pg.close();
            } catch (Exception ignored) {
                // shutting down anyway
            }
        }));
        System.setProperty("spring.datasource.url", pg.getJdbcUrl("postgres", "postgres"));
        System.setProperty("spring.datasource.username", "postgres");
        System.setProperty("spring.datasource.password", "");
        SpringApplication app = new SpringApplication(FarmerAccountsApplication.class);
        app.setAdditionalProfiles("dev");
        app.run(args);
    }
}
