package com.inviz.visioncerti.store;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@link PostgresStore#verifyRuntimeRole()} against a real PostgreSQL server with real roles. Needs the embedded
 * server's superuser to create the roles; with an external test database these cases are skipped (reported).
 */
class RuntimeRoleCheckTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static PostgresTestDatabase.Database db;

    @BeforeAll
    static void start() {
        db = PostgresTestDatabase.create("roles");
    }

    /** Creates a role (a login unless NOLOGIN) with the given attributes/memberships; returns its name. */
    private static String login(String attributes, String... grants) throws Exception {
        assumeTrue(db.adminUrl() != null, "role cases need the embedded server's superuser");
        String name = "vc_role_case_" + SEQ.incrementAndGet() + "_" + System.nanoTime() % 100000;
        try (Connection c = db.adminConnect(); Statement s = c.createStatement()) {
            String login = attributes.contains("NOLOGIN") ? "" : "LOGIN PASSWORD 'role-case' ";
            s.execute("CREATE ROLE " + name + " " + login + attributes);
            for (String grant : grants) {
                s.execute(grant.replace("{role}", name));
            }
        }
        return name;
    }

    private static PostgresStore storeAs(String user) {
        return new PostgresStore(StoreConfig.dataSource(db.url(), user, db.passwordFile(), 2, false),
                Duration.ofSeconds(5));
    }

    private static void assertRefused(String user, String message) throws Exception {
        try (PostgresStore store = storeAs(user)) {
            assertThatThrownBy(store::verifyRuntimeRole).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("FAIL_CLOSED").hasMessageContaining(message);
        }
    }

    @Test
    void certiAppMemberThatOwnsNothingIsAccepted() throws Exception {
        try (PostgresStore store = storeAs(db.username())) {
            assertThatCode(store::verifyRuntimeRole).doesNotThrowAnyException();
        }
        String plain = login("NOSUPERUSER NOBYPASSRLS", "GRANT certi_app TO {role}");
        try (PostgresStore store = storeAs(plain)) {
            assertThatCode(store::verifyRuntimeRole).doesNotThrowAnyException();
        }
    }

    @Test
    void superuserOrRoleThatCanBecomeSuperuserIsRefused() throws Exception {
        assertRefused(login("SUPERUSER", "GRANT certi_app TO {role}"), "superuser");
        assertRefused(login("NOSUPERUSER", "GRANT certi_app TO {role}", "GRANT postgres TO {role}"), "superuser");
    }

    @Test
    void bypassRlsDirectOrInheritedIsRefused() throws Exception {
        assertRefused(login("NOSUPERUSER BYPASSRLS", "GRANT certi_app TO {role}"), "BYPASSRLS");
        String bypass = login("NOLOGIN BYPASSRLS");
        assertRefused(login("NOSUPERUSER", "GRANT certi_app TO {role}", "GRANT " + bypass + " TO {role}"),
                "BYPASSRLS");
    }

    @Test
    void schemaOwnerOrMemberOfTheOwnerIsRefused() throws Exception {
        PostgresTestDatabase.Database own = PostgresTestDatabase.create("owner");
        assumeTrue(own.adminUrl() != null);
        String owner = login("NOSUPERUSER", "GRANT certi_app TO {role}");
        try (Connection c = own.adminConnect(); Statement s = c.createStatement()) {
            s.execute("ALTER SCHEMA certi_rt OWNER TO " + owner);
        }
        String ownerMember = login("NOSUPERUSER", "GRANT certi_app TO {role}", "GRANT " + owner + " TO {role}");
        for (String user : new String[] {owner, ownerMember}) {
            try (PostgresStore store = new PostgresStore(StoreConfig.dataSource(own.url(), user, own.passwordFile(), 2,
                    false), Duration.ofSeconds(5))) {
                assertThatThrownBy(store::verifyRuntimeRole).hasMessageContaining("FAIL_CLOSED")
                        .hasMessageContaining("owns");
            }
        }
    }

    @Test
    void loginOutsideCertiAppIsRefused() throws Exception {
        assertRefused(login("NOSUPERUSER NOBYPASSRLS"), "not a member of certi_app");
    }
}
