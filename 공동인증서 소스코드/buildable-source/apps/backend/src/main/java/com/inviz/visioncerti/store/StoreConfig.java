package com.inviz.visioncerti.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Selects the runtime store. {@code prod} requires PostgreSQL (no fallback); {@code dev} defaults to the in-memory
 * store and may opt into PostgreSQL. The password is read from a file only and never logged.
 */
@Configuration
public class StoreConfig {

    @Bean
    Store store(Environment environment,
            @Value("${visioncerti.persistence.mode:}") String mode,
            @Value("${visioncerti.db.url:}") String url,
            @Value("${visioncerti.db.username:}") String username,
            @Value("${visioncerti.db.password-file:}") String passwordFile,
            @Value("${visioncerti.db.pool-size:10}") int poolSize,
            @Value("${visioncerti.db.lock-timeout:PT5S}") Duration lockTimeout) {
        boolean prod = environment.acceptsProfiles(Profiles.of("prod", "production"));
        String selected = mode == null || mode.isBlank() ? (prod ? Store.MODE_POSTGRESQL : Store.MODE_MEMORY)
                : mode.trim().toUpperCase(Locale.ROOT);
        if (Store.MODE_MEMORY.equals(selected) || "MEMORY".equals(selected)) {
            if (prod) {
                throw new IllegalStateException("FAIL_CLOSED: in-memory persistence is not allowed in prod");
            }
            return new MemoryStore();
        }
        if (!Store.MODE_POSTGRESQL.equals(selected)) {
            throw new IllegalStateException("FAIL_CLOSED: unknown VISIONCERTI_PERSISTENCE_MODE");
        }
        HikariDataSource dataSource = dataSource(url, username, passwordFile, poolSize, prod);
        PostgresStore store = new PostgresStore(dataSource, lockTimeout);
        try {
            store.verifySchema();
            if (prod) {
                store.verifyRuntimeRole();
            }
        } catch (RuntimeException e) {
            dataSource.close();
            throw e;
        }
        return store;
    }

    public static HikariDataSource dataSource(String url, String username, String passwordFile, int poolSize,
            boolean requireTls) {
        String problem = problem(url, username, passwordFile, requireTls);
        if (problem != null) {
            throw new IllegalStateException("FAIL_CLOSED: " + problem);
        }
        String password;
        try {
            password = Files.readString(Path.of(passwordFile), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalStateException("FAIL_CLOSED: VISIONCERTI_DB_PASSWORD_FILE is not readable");
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("vision-certi-runtime");
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(Math.max(2, Math.min(poolSize, 50)));
        config.setAutoCommit(false);
        config.setConnectionTimeout(5_000);
        config.setInitializationFailTimeout(10_000);
        config.addDataSourceProperty("ApplicationName", "vision-certi-backend");
        return new HikariDataSource(config);
    }

    /** Null when usable; otherwise a reason naming variables, never values. See {@link JdbcUrlCheck}. */
    public static String problem(String url, String username, String passwordFile, boolean requireTls) {
        String urlProblem = JdbcUrlCheck.problem(url, requireTls);
        if (urlProblem != null) {
            return urlProblem;
        }
        if (username == null || username.isBlank()) {
            return "VISIONCERTI_DB_USERNAME is required";
        }
        if (passwordFile == null || passwordFile.isBlank() || !Files.isReadable(Path.of(passwordFile))) {
            return "VISIONCERTI_DB_PASSWORD_FILE must name a readable file";
        }
        return null;
    }
}
