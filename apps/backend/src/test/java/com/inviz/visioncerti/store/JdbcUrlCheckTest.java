package com.inviz.visioncerti.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Bypass attempts against the VISIONCERTI_DB_URL rules (prod: TLS required for non-loopback hosts). */
class JdbcUrlCheckTest {

    private static final String REMOTE = "jdbc:postgresql://db.example:5432/vision_certi";

    private static String prod(String url) {
        return JdbcUrlCheck.problem(url, true);
    }

    @Test
    void acceptsExactVerifyFullAndLoopbackWithoutTls() {
        assertThat(prod(REMOTE + "?sslmode=verify-full")).isNull();
        assertThat(prod(REMOTE + "?sslmode=verify-full&sslrootcert=/etc/vc/db-ca.pem&ApplicationName=vc")).isNull();
        // Values are decoded exactly like the driver does.
        assertThat(prod(REMOTE + "?sslmode=verify%2Dfull")).isNull();
        assertThat(prod("jdbc:postgresql://127.0.0.1:5432/vision_certi")).isNull();
        assertThat(prod("jdbc:postgresql://localhost/vision_certi")).isNull();
        assertThat(prod("jdbc:postgresql://[::1]:5432/vision_certi")).isNull();
        assertThat(JdbcUrlCheck.problem(REMOTE, false)).isNull();
    }

    @Test
    void substringOrWeakerSslModesAreRejected() {
        assertThat(prod(REMOTE)).contains("exactly sslmode=verify-full");
        assertThat(prod(REMOTE + "?sslmode=verify-ca")).contains("exactly sslmode=verify-full");
        assertThat(prod(REMOTE + "?sslmode=require")).contains("exactly sslmode=verify-full");
        assertThat(prod(REMOTE + "?sslmode=disable&applicationName=sslmode=verify-full"))
                .contains("exactly sslmode=verify-full");
        assertThat(prod(REMOTE + "?sslmode=disable%26sslmode%3Dverify-full")).contains("exactly sslmode=verify-full");
        assertThat(prod(REMOTE + "?sslmode=verify-fullx")).contains("exactly sslmode=verify-full");
        assertThat(prod(REMOTE + "?sslmode=VERIFY-FULL")).contains("exactly sslmode=verify-full");
        assertThat(prod(REMOTE + "?x=1#sslmode=verify-full")).contains("fragment");
        // A mixed host list is not loopback.
        assertThat(prod("jdbc:postgresql://localhost,db.example/vision_certi")).contains("verify-full");
        assertThat(prod("jdbc:postgresql://localhost.db.example/vision_certi")).contains("verify-full");
    }

    @Test
    void duplicateOrDisguisedParametersAreRejected() {
        assertThat(prod(REMOTE + "?sslmode=verify-full&sslmode=disable")).contains("repeats");
        assertThat(prod(REMOTE + "?sslmode=disable&sslmode=verify-full")).contains("repeats");
        assertThat(prod(REMOTE + "?sslmode=verify-full&SSLMODE=disable")).contains("repeats");
        // The driver ignores a differently cased or encoded name, so it cannot stand in for sslmode.
        assertThat(prod(REMOTE + "?SSLMODE=verify-full")).contains("exactly as sslmode");
        assertThat(prod(REMOTE + "?ss%6Cmode=verify-full")).contains("must not be encoded");
        assertThat(prod(REMOTE + "?sslmode=%zz")).contains("malformed");
        assertThat(prod(REMOTE + "?sslmode=verify-full&")).contains("empty");
    }

    @Test
    void credentialAndConnectionOverridesAreRejectedEvenOnLoopback() {
        String local = "jdbc:postgresql://127.0.0.1:5432/vision_certi";
        assertThat(prod(local + "?user=postgres")).contains("must not set user, password");
        assertThat(prod(local + "?USER=postgres")).contains("must not set user, password");
        assertThat(prod(local + "?password=x")).contains("must not set user, password");
        assertThat(prod(local + "?Password=x")).contains("must not set user, password");
        assertThat(prod(local + "?pass%77ord=x")).contains("must not be encoded");
        assertThat(prod(local + "?service=other-db")).contains("must not set");
        assertThat(prod(local + "?options=-c%20role%3Dpostgres")).contains("must not set");
        assertThat(prod(REMOTE + "?sslmode=verify-full&sslfactory=org.postgresql.ssl.NonValidatingFactory"))
                .contains("SSL factory");
        assertThat(prod(REMOTE + "?sslmode=verify-full&sslhostnameverifier=x")).contains("SSL factory");
        assertThat(prod("jdbc:postgresql://postgres:secret@db.example/vision_certi?sslmode=verify-full"))
                .contains("user information");
        assertThat(prod("jdbc:postgresql://postgres@127.0.0.1/vision_certi")).contains("user information");
        assertThat(prod("jdbc:postgresql://127.0.0.1%2Edb.example/vision_certi")).contains("encoded");
        assertThat(prod("jdbc:h2:mem:x")).contains("jdbc:postgresql://");
        assertThat(prod("jdbc:postgresql:///vision_certi")).contains("host");
    }

    @Test
    void messagesNeverContainTheUrlOrItsValues() {
        String problem = prod("jdbc:postgresql://admin:TopSecret@db.example/vision_certi?password=TopSecret");
        assertThat(problem).doesNotContain("TopSecret").doesNotContain("db.example").doesNotContain("admin");
    }
}
