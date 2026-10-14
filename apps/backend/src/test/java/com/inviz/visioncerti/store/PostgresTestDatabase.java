package com.inviz.visioncerti.store;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Real PostgreSQL for persistence tests (never H2/PGlite).
 *
 * <p>Default: the zonky embedded PostgreSQL binaries (an actual {@code postgres} server process in a temporary data
 * directory on a random loopback port). Each call to {@link #create} makes a fresh database with migration V005 applied
 * by the superuser, and the application connects as {@code vc_runtime_test}, a login that is only a member of
 * {@code certi_app} - so table grants and immutability triggers are exercised exactly as in production.
 *
 * <p>Alternative: set {@code VISIONCERTI_TEST_PG_URL}, {@code VISIONCERTI_TEST_PG_USERNAME} and
 * {@code VISIONCERTI_TEST_PG_PASSWORD_FILE} to an isolated, disposable database (role {@code certi_app} must exist);
 * {@link #create} then drops and recreates schema {@code certi_rt} there. The password is read from the file and
 * never printed.
 */
public final class PostgresTestDatabase {

    /** Connection settings for one test database. */
    public record Database(String url, String username, String passwordFile, String engine, String adminUrl) {

        /**
         * A connection with the schema owner's rights (embedded: superuser; external: the configured owner login),
         * used only by tests that simulate tampering or a crashed instance with direct SQL.
         */
        public Connection adminConnect() throws SQLException {
            return adminUrl == null ? connect() : DriverManager.getConnection(adminUrl);
        }

        public Connection connect() throws SQLException {
            try {
                return DriverManager.getConnection(url, username, Files.readString(Path.of(passwordFile)).strip());
            } catch (IOException e) {
                throw new SQLException("password file not readable", e);
            }
        }

        /** Spring properties selecting this database for the runtime store. */
        public void register(DynamicPropertyRegistry registry) {
            registry.add("visioncerti.persistence.mode", () -> Store.MODE_POSTGRESQL);
            registry.add("visioncerti.db.url", this::url);
            registry.add("visioncerti.db.username", this::username);
            registry.add("visioncerti.db.password-file", this::passwordFile);
        }

        public PostgresStore store() {
            return new PostgresStore(StoreConfig.dataSource(url, username, passwordFile, 4, false),
                    java.time.Duration.ofSeconds(5));
        }
    }

    private static final String RUNTIME_USER = "vc_runtime_test";
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static EmbeddedPostgres embedded;
    private static Path runtimePasswordFile;

    private PostgresTestDatabase() {
    }

    public static synchronized Database create(String label) {
        try {
            String externalUrl = System.getenv("VISIONCERTI_TEST_PG_URL");
            if (externalUrl != null && !externalUrl.isBlank()) {
                return external(externalUrl);
            }
            return embedded(label);
        } catch (Exception e) {
            throw new IllegalStateException("test PostgreSQL unavailable: " + e.getMessage(), e);
        }
    }

    private static Database external(String url) throws Exception {
        Database db = new Database(url, System.getenv("VISIONCERTI_TEST_PG_USERNAME"),
                System.getenv("VISIONCERTI_TEST_PG_PASSWORD_FILE"), "external", null);
        try (Connection c = db.connect(); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS certi_rt CASCADE");
            s.execute(migration());
        }
        return db;
    }

    private static Database embedded(String label) throws Exception {
        if (embedded == null) {
            embedded = EmbeddedPostgres.builder().start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    embedded.close();
                } catch (IOException ignored) {
                    // temporary data directory is removed by the library when possible
                }
            }));
            runtimePasswordFile = Files.createTempFile("vc-runtime-test", ".password");
            Files.writeString(runtimePasswordFile, "runtime-test-password", StandardCharsets.UTF_8);
            runtimePasswordFile.toFile().deleteOnExit();
            try (Connection c = embedded.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
                s.execute("CREATE ROLE certi_app NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
                s.execute("CREATE ROLE " + RUNTIME_USER + " LOGIN PASSWORD 'runtime-test-password' NOSUPERUSER"
                        + " NOCREATEDB NOCREATEROLE IN ROLE certi_app");
            }
        }
        String name = "vc_" + label.toLowerCase().replaceAll("[^a-z0-9]", "") + "_" + COUNTER.incrementAndGet();
        try (Connection c = embedded.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        }
        String superUrl = embedded.getJdbcUrl("postgres", name);
        try (Connection c = DriverManager.getConnection(superUrl); Statement s = c.createStatement()) {
            s.execute(migration());
        }
        String url = superUrl.replaceFirst("\\?.*$", "");
        return new Database(url, RUNTIME_USER, runtimePasswordFile.toString(), "embedded " + version(superUrl),
                superUrl);
    }

    private static String version(String url) throws SQLException {
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SHOW server_version")) {
            rs.next();
            return "PostgreSQL " + rs.getString(1);
        }
    }

    /** The reviewed migration file itself (database/migrations/V005__production_runtime.sql). */
    public static String migration() throws IOException {
        Path file = Path.of("..", "..", "database", "migrations", "V005__production_runtime.sql");
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
