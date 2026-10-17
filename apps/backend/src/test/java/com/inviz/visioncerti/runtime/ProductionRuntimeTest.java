package com.inviz.visioncerti.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inviz.visioncerti.VisionCertiApplication;
import com.inviz.visioncerti.certificate.TestPki;
import com.inviz.visioncerti.common.Hashing;
import com.inviz.visioncerti.doctor.credential.FakeOpenBao;
import com.inviz.visioncerti.doctor.credential.OpenBaoCredentialProvider;
import com.inviz.visioncerti.integration.IntegrationAuthFilter;
import com.inviz.visioncerti.integration.IntegrationClients;
import com.inviz.visioncerti.security.StaffTokenVerifier;
import com.inviz.visioncerti.security.TestOidc;
import com.inviz.visioncerti.store.PostgresTestDatabase;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.Filter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The {@code prod} profile, actually running: PostgreSQL (real server, V005, non-owner login), OIDC staff tokens,
 * OpenBao Transit + PKI over HTTPS with separate token files (loopback fake that issues real X.509 certificates and
 * signs with real ECDSA keys), PC certificate trust anchors with fresh CRLs, HMAC PACS integration.
 *
 * <p>Instances are started and stopped explicitly to prove durability: sessions, enrollments, credentials, consumed
 * challenges, nonces, handoff codes and idempotency records survive a restart; two instances running at the same time
 * on the same database cannot produce two signatures for one request. The only test substitution is the staff token
 * key source (an in-memory JWKS instead of the identity provider's URL); verification itself is the production code.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProductionRuntimeTest {

    private static final String A = "hospital-a";
    private static final String B = "hospital-b";
    private static final String PACS = "pacs-sv70";
    private static final String PACS_SECRET = "prod-test-pacs-secret-0123456789abcdefghijklmn";
    private static final String CONSENT =
            "{\"version\":\"2026-10-13-draft\",\"authentication\":true,\"signature\":true,\"privacy\":true}";
    static final TestOidc OIDC = new TestOidc(Clock.systemUTC());
    private static final AtomicInteger SEQ = new AtomicInteger(7000);

    /** Replaces only the JWKS location (in-memory test keys); issuer/audience checks are the production verifier's. */
    @Configuration
    static class TestIdentityProvider {
        @Bean
        @Primary
        StaffTokenVerifier testStaffTokenVerifier() {
            return OIDC.verifier();
        }
    }

    private PostgresTestDatabase.Database db;
    private FakeOpenBao bao;
    private TestPki pki;
    private Path dir;
    private Path crlRoot;
    private Path crlIssuing;
    private final List<ConfigurableApplicationContext> running = new ArrayList<>();

    // State carried between the ordered steps.
    private Doctor doctor;
    private String consumedLoginChallenge;
    private String usedNonce;
    private String redeemedHandoff;
    private String idempotencyKey;
    private String idempotentBody;
    private String idempotentResponse;

    record Doctor(String id, String token, String registrationId, TestPki.Leaf leaf, String keyName, String serial) {
    }

    @BeforeAll
    void setUp() throws Exception {
        db = PostgresTestDatabase.create("prodruntime");
        bao = new FakeOpenBao(true);
        pki = new TestPki(Clock.systemUTC(), "ProdRuntime");
        dir = Files.createTempDirectory("vc-prod-runtime");
        Files.writeString(dir.resolve("anchors.pem"), TestPki.pem(pki.root.certificate()));
        crlRoot = dir.resolve("root.crl");
        crlIssuing = dir.resolve("issuing.crl");
        writeCrls();
        Files.writeString(dir.resolve("provisioning.token"), FakeOpenBao.provisioningToken());
        Files.writeString(dir.resolve("signing.token"), FakeOpenBao.signingToken());
        Files.writeString(dir.resolve("pacs.secret"), PACS_SECRET);
        Files.writeString(dir.resolve("staff-bootstrap.json"), "["
                + staffEntry(A, "admin-a-subject", "Admin A")
                + "," + staffEntry(B, "admin-b-subject", "Admin B") + "]");
    }

    @AfterAll
    void tearDown() {
        running.forEach(ConfigurableApplicationContext::close);
        bao.close();
    }

    private static String staffEntry(String tenant, String subject, String name) {
        return "{\"tenantId\":\"" + tenant + "\",\"issuer\":\"" + TestOidc.ISSUER + "\",\"subject\":\"" + subject
                + "\",\"role\":\"ADMIN\",\"displayName\":\"" + name + "\",\"evidenceRef\":\"evidence://hr/" + subject
                + "\"}";
    }

    private void writeCrls(X509Certificate... revoked) throws Exception {
        List<X509CRL> crls = pki.freshCrls(revoked);
        Files.write(crlRoot, crls.get(0).getEncoded());
        Files.write(crlIssuing, crls.get(1).getEncoded());
    }

    private Map<String, Object> prodProperties() {
        Map<String, Object> p = new HashMap<>();
        p.put("visioncerti.doctor.admin-invitations-enabled", "true"); // legacy lifecycle scenarios opt in
        p.put("server.port", "0");
        p.put("visioncerti.db.url", db.url());
        p.put("visioncerti.db.username", db.username());
        p.put("visioncerti.db.password-file", db.passwordFile());
        p.put("visioncerti.staff-auth.oidc.issuer", TestOidc.ISSUER);
        p.put("visioncerti.staff-auth.oidc.audience", TestOidc.AUDIENCE);
        p.put("visioncerti.staff-auth.oidc.jwks-uri", "https://idp.test.invalid/realms/vision-certi/certs");
        p.put("visioncerti.staff-auth.oidc.client-id", TestOidc.CLIENT_ID);
        p.put("visioncerti.staff-auth.bootstrap-file", dir.resolve("staff-bootstrap.json").toString());
        p.put("visioncerti.openbao.addr", bao.addr());
        p.put("visioncerti.openbao.ca-cert-file", bao.tlsCaFile().toString());
        p.put("visioncerti.openbao.provisioning-token-file", dir.resolve("provisioning.token").toString());
        p.put("visioncerti.openbao.signing-token-file", dir.resolve("signing.token").toString());
        p.put("visioncerti.openbao.cert-ttl", "720h");
        p.put("visioncerti.cert.trust-anchors-file", dir.resolve("anchors.pem").toString());
        p.put("visioncerti.cert.crl-files", crlRoot + "," + crlIssuing);
        p.put("visioncerti.cert.service-id", "vision-certi-prod-test");
        p.put("visioncerti.pacs.client-id", PACS);
        p.put("visioncerti.pacs.client-secret-file", dir.resolve("pacs.secret").toString());
        p.put("visioncerti.pacs.tenant-ids", A);
        return p;
    }

    private ConfigurableApplicationContext startProd(Map<String, Object> overrides) {
        Map<String, Object> properties = prodProperties();
        properties.putAll(overrides);
        // Command-line arguments: highest precedence, like real deployment settings (they beat application*.yml).
        String[] args = properties.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
        ConfigurableApplicationContext context = new SpringApplicationBuilder(VisionCertiApplication.class,
                TestIdentityProvider.class)
                .profiles("prod")
                .web(WebApplicationType.SERVLET)
                .run(args);
        running.add(context);
        return context;
    }

    private ConfigurableApplicationContext startProd() {
        return startProd(Map.of());
    }

    private void stop(ConfigurableApplicationContext context) {
        context.close();
        running.remove(context);
    }

    private static MockMvc mvc(ConfigurableApplicationContext context) {
        List<Filter> filters = new ArrayList<>(context.getBeansOfType(Filter.class).values());
        AnnotationAwareOrderComparator.sort(filters);
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                .addFilters(filters.toArray(Filter[]::new)).build();
    }

    // ------------------------------------------------------------------ request helpers

    private static <T extends AbstractMockHttpServletRequestBuilder<?>> T staff(T builder, String subject) {
        builder.header("Authorization", "Bearer " + OIDC.token(subject));
        return builder;
    }

    private static <T extends AbstractMockHttpServletRequestBuilder<?>> T bearer(T builder, String token) {
        builder.header("Authorization", "Bearer " + token);
        return builder;
    }

    private static MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder b, String method, String path,
            String body, String nonce) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String sha = Hashing.sha256Hex(bytes);
        String ts = Long.toString(Instant.now().getEpochSecond());
        String signature = IntegrationAuthFilter.sign(PACS_SECRET.getBytes(StandardCharsets.UTF_8),
                IntegrationAuthFilter.canonical(IntegrationClients.DEFAULT_AUDIENCE, PACS, method, path, null, ts,
                        nonce, sha));
        return b.header("X-VC-Client-Id", PACS).header("X-VC-Timestamp", ts).header("X-VC-Nonce", nonce)
                .header("X-VC-Content-SHA256", sha).header("X-VC-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON).content(bytes);
    }

    private static String nonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private String certificateLogin(MockMvc mvc, TestPki.Leaf leaf, int expectedStatus, String[] challengeOut)
            throws Exception {
        String login = body(mvc.perform(post("/v1/certificate-enrollment/login-challenges"))
                .andExpect(status().isCreated()));
        String challengeId = JsonPath.read(login, "$.challengeId");
        if (challengeOut != null) {
            challengeOut[0] = challengeId;
        }
        byte[] payload = Base64.getDecoder().decode((String) JsonPath.read(login, "$.signedPayloadBase64"));
        String session = body(mvc.perform(post("/v1/certificate-enrollment/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(challengeId, leaf, payload)))
                .andExpect(status().is(expectedStatus)));
        return expectedStatus == 200 ? JsonPath.read(session, "$.activationToken") : session;
    }

    private String loginBody(String challengeId, TestPki.Leaf leaf, byte[] payload) {
        return "{\"challengeId\":\"" + challengeId + "\",\"fingerprintSha256\":\"" + leaf.fingerprint()
                + "\",\"certificate\":\"" + leaf.der() + "\",\"chain\":[\"" + pki.intermediateDer()
                + "\"],\"signature\":\"" + TestPki.sign(leaf.keys(), "SHA256withRSA", payload)
                + "\",\"signatureAlgorithm\":\"SHA256withRSA\"}";
    }

    /** Preregistration → certificate proof → consent → admin review (all real prod routes); not yet activated. */
    private Doctor reviewedDoctor(MockMvc mvc) throws Exception {
        String pre = body(mvc.perform(staff(post("/v1/admin/doctors"), "admin-a-subject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"최판독\",\"licenseNumber\":\"" + SEQ.incrementAndGet()
                                + "\",\"centerName\":\"서울영상센터\",\"googleEmail\":\"prod" + SEQ.get()
                                + "@example.com\",\"identityEvidenceRef\":\"evidence://id\","
                                + "\"licenseEvidenceRef\":\"evidence://lic\"}"))
                .andExpect(status().isCreated()));
        String doctorId = JsonPath.read(pre, "$.doctor.id");
        String challenge = body(mvc.perform(post("/v1/certificate-enrollment/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invitationCode\":\"" + JsonPath.read(pre, "$.invitationCode") + "\"}"))
                .andExpect(status().isCreated()));
        TestPki.Leaf leaf = pki.leaf(TestPki.rsa(2048));
        byte[] payload = Base64.getDecoder().decode((String) JsonPath.read(challenge, "$.signedPayloadBase64"));
        String session = body(mvc.perform(post("/v1/certificate-enrollment/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationId\":\"" + JsonPath.read(challenge, "$.registrationId")
                                + "\",\"certificate\":\"" + leaf.der() + "\",\"chain\":[\"" + pki.intermediateDer()
                                + "\"],\"signature\":\"" + TestPki.sign(leaf.keys(), "SHA256withRSA", payload)
                                + "\",\"signatureAlgorithm\":\"SHA256withRSA\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enrollment.kind").value("PC_CERTIFICATE")));
        String token = JsonPath.read(session, "$.activationToken");
        String registrationId = JsonPath.read(session, "$.enrollment.registrationId");
        mvc.perform(bearer(post("/v1/certificate-enrollment/enrollments/" + registrationId + "/consent"), token)
                .contentType(MediaType.APPLICATION_JSON).content(CONSENT)).andExpect(status().isOk());
        mvc.perform(staff(post("/v1/admin/doctors/" + doctorId + "/certificate-reviews"), "admin-a-subject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationId\":\"" + registrationId + "\",\"decision\":\"APPROVE\","
                                + "\"fingerprintSha256\":\"" + leaf.fingerprint()
                                + "\",\"evidenceRef\":\"evidence://license-scan\"}"))
                .andExpect(status().isOk());
        return new Doctor(doctorId, token, registrationId, leaf, null, null);
    }

    private Doctor activate(MockMvc mvc, Doctor reviewed) throws Exception {
        String activated = body(mvc.perform(bearer(post("/v1/doctor-activation/activate"), reviewed.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.certificate.kind").value("X509")));
        return new Doctor(reviewed.id(), reviewed.token(), reviewed.registrationId(), reviewed.leaf(),
                JsonPath.read(activated, "$.certificate.keyName"), JsonPath.read(activated, "$.certificate.serialNumber"));
    }

    private static String snapshot(String doctorId, String reportId, int revision) {
        return "{\"doctorId\":\"" + doctorId + "\",\"report\":{\"conclusion\":\"no acute\",\"externalReportId\":\""
                + reportId + "\",\"reading\":\"prod runtime\",\"revision\":" + revision
                + "},\"schema\":\"saintview-pacs-report-snapshot/v1\",\"tenantId\":\"" + A + "\"}";
    }

    private static String reportRequest(String doctorId, String reportId, int revision) {
        String snapshot = snapshot(doctorId, reportId, revision);
        byte[] bytes = snapshot.getBytes(StandardCharsets.UTF_8);
        return "{\"tenantId\":\"" + A + "\",\"doctorId\":\"" + doctorId + "\",\"externalReportId\":\"" + reportId
                + "\",\"externalStudyId\":\"pacs-sv70:study:1\",\"revision\":" + revision
                + ",\"title\":\"CT report\",\"contentType\":\"application/vnd.saintview.pacs-report+json;version=1\","
                + "\"contentBase64\":\"" + Base64.getEncoder().encodeToString(bytes) + "\",\"contentSha256\":\""
                + Hashing.sha256Hex(bytes) + "\"}";
    }

    private static ResultActions createReport(MockMvc mvc, String key, String body) throws Exception {
        String path = "/v1/integrations/pacs/report-sign-requests";
        return mvc.perform(signed(post(path), "POST", path, body, nonce()).header("Idempotency-Key", key));
    }

    private String approveBody(MockMvc mvc, String token, String requestId) throws Exception {
        String request = body(mvc.perform(bearer(get("/v1/doctor/sign-requests/" + requestId), token))
                .andExpect(status().isOk()));
        String payload = JsonPath.read(body(mvc.perform(bearer(get("/v1/doctor/sign-requests/" + requestId
                + "/certificate-payload"), token)).andExpect(status().isOk())), "$.signedPayloadBase64");
        String signature = TestPki.sign(doctor.leaf().keys(), "SHA256withRSA", Base64.getDecoder().decode(payload));
        return "{\"documentSha256\":\"" + JsonPath.read(request, "$.documentSha256") + "\",\"nonce\":\""
                + JsonPath.read(request, "$.nonce") + "\",\"reviewAttestation\":\"REVIEWED_EXACT_REPORT_CONTENT\""
                + ",\"certificateDerBase64\":\"" + doctor.leaf().der() + "\",\"externalSignatureBase64\":\""
                + signature + "\",\"externalSignatureAlgorithm\":\"SHA256withRSA\"}";
    }

    private long countSql(String sql, Object... params) throws SQLException {
        try (Connection c = db.adminConnect(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    @Order(1)
    void prodRefusesToStartWithIncompleteConfigurationAndNamesWhatIsMissing() {
        assertThatThrownBy(() -> startProd(Map.of("visioncerti.staff-auth.oidc.issuer", "",
                "visioncerti.openbao.ca-cert-file", "", "visioncerti.cert.crl-files", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAIL_CLOSED")
                .hasMessageContaining("VISIONCERTI_STAFF_OIDC_ISSUER")
                .hasMessageContaining("VISIONCERTI_OPENBAO_CA_CERT_FILE")
                .hasMessageContaining("CRL");
        assertThatThrownBy(() -> startProd(Map.of("visioncerti.doctor.signing-backend", "DEV_SIMULATED")))
                .hasMessageContaining("VISIONCERTI_SIGNING_BACKEND must be OPENBAO_TRANSIT");
        assertThatThrownBy(() -> startProd(Map.of("visioncerti.openbao.provisioning-token", "inline-token")))
                .hasMessageContaining("not environment values");
        assertThatThrownBy(() -> startProd(Map.of("visioncerti.staff-auth.mode", "DEV_HEADERS")))
                .hasMessageContaining("VISIONCERTI_STAFF_AUTH_MODE must be OIDC");
        assertThatThrownBy(() -> startProd(Map.of("visioncerti.mobile.provider", "some-carrier")))
                .hasMessageContaining("no mobile adapter is implemented");
        assertThatThrownBy(() -> startProd(Map.of("visioncerti.db.url",
                "jdbc:postgresql://db.example:5432/vision_certi")))
                .hasMessageContaining("sslmode=verify-full");
        assertThatThrownBy(() -> startProd(Map.of("visioncerti.cert.dev-mock-enabled", "true")))
                .hasMessageContaining("VISIONCERTI_CERT_DEV_MOCK_ENABLED must be false");
        assertThatThrownBy(() -> startProd(Map.of("visioncerti.db.url",
                "jdbc:postgresql://db.example:5432/vision_certi?sslmode=verify-ca")))
                .hasMessageContaining("exactly sslmode=verify-full");
        assertThatThrownBy(() -> startProd(Map.of("visioncerti.db.url",
                "jdbc:postgresql://db.example:5432/vision_certi?sslmode=verify-full&sslmode=disable")))
                .hasMessageContaining("repeats a query parameter");
        assertThatThrownBy(() -> startProd(Map.of("visioncerti.db.url", db.url() + "?user=postgres")))
                .hasMessageContaining("must not set user, password");
        if (db.adminUrl() != null) {
            // The connected role itself is checked: a superuser login is refused even with a valid URL.
            assertThatThrownBy(() -> startProd(Map.of("visioncerti.db.username", "postgres")))
                    .hasStackTraceContaining("FAIL_CLOSED: the runtime database login is (or can become) a superuser");
        }
        assertThat(running).isEmpty();
    }

    @Test
    @Order(2)
    void prodRunsWithHonestCapabilitiesAndNoDevOrMockSurface() throws Exception {
        ConfigurableApplicationContext app = startProd();
        MockMvc mvc = mvc(app);
        String caps = body(mvc.perform(get("/v1/runtime/capabilities")).andExpect(status().isOk()));
        assertThat((String) JsonPath.read(caps, "$.profile")).isEqualTo("prod");
        assertThat((String) JsonPath.read(caps, "$.persistence.mode")).isEqualTo("POSTGRESQL");
        assertThat((Integer) JsonPath.read(caps, "$.persistence.schemaVersion")).isEqualTo(8);
        assertThat((String) JsonPath.read(caps, "$.staffAuth.mode")).isEqualTo("OIDC");
        assertThat((String) JsonPath.read(caps, "$.staffAuth.issuer")).isEqualTo(TestOidc.ISSUER);
        assertThat((String) JsonPath.read(caps, "$.staffAuth.clientId")).isEqualTo(TestOidc.CLIENT_ID);
        assertThat((String) JsonPath.read(caps, "$.signing.backend")).isEqualTo("OPENBAO_TRANSIT");
        assertThat((Boolean) JsonPath.read(caps, "$.signing.available")).isTrue();
        assertThat((String) JsonPath.read(caps, "$.signing.signatureMode"))
                .isEqualTo(OpenBaoCredentialProvider.PROD_SIGNATURE_MODE);
        assertThat((String) JsonPath.read(caps, "$.signing.pki")).isEqualTo("PRIVATE_OPENBAO_PKI");
        assertThat((String) JsonPath.read(caps, "$.doctorAuth.pcCertificate.crlState")).isEqualTo("FRESH");
        assertThat((Boolean) JsonPath.read(caps, "$.doctorAuth.devMockCertificate")).isFalse();
        assertThat((Boolean) JsonPath.read(caps, "$.doctorAuth.devIdentity")).isFalse();
        assertThat((Boolean) JsonPath.read(caps, "$.personalSignup.available")).isFalse();
        assertThat((Boolean) JsonPath.read(caps, "$.clinicalUseAllowed")).isFalse();
        assertThat((List<String>) JsonPath.read(caps, "$.warnings")).contains("PRIVATE_PKI_NOT_ACCREDITED",
                "NO_TRUSTED_TIMESTAMP", "MOBILE_PROVIDER_NOT_CONFIGURED")
                .doesNotContain("IN_MEMORY_PERSISTENCE_NOT_DURABLE", "DEV_HEADER_IDENTITIES_NOT_AUTHENTICATION");
        assertThat(caps).doesNotContain(FakeOpenBao.provisioningToken()).doesNotContain(PACS_SECRET)
                .doesNotContain(db.passwordFile());
        mvc.perform(get("/v1/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.persistence").value("POSTGRESQL"))
                .andExpect(jsonPath("$.devSyntheticIdentities").value(false));

        // Dev and mock routes do not exist; personal signup is explicitly unavailable; dev headers are refused.
        mvc.perform(post("/v1/doctor-activation/dev-identity").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/v1/certificate-enrollment/dev/mock-sign").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isNotFound());
        mvc.perform(post("/v1/enrollment/phone-challenges").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"01012345678\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SMS_NOT_CONFIGURED"));
        mvc.perform(get("/v1/documents").header("X-Tenant-Id", A).header("X-Actor-Id", "admin-demo"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("HEADER_OVERRIDE_REJECTED"));
        mvc.perform(staff(get("/v1/staff/me"), "admin-a-subject").header("X-Tenant-Id", B))
                .andExpect(status().isBadRequest());
        stop(app);
    }

    @Test
    @Order(3)
    void staffAuthenticationIsVerifiedOidcPlusExplicitMappingOnly() throws Exception {
        ConfigurableApplicationContext app = startProd();
        MockMvc mvc = mvc(app);
        mvc.perform(get("/v1/staff/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("STAFF_TOKEN_REQUIRED"));
        mvc.perform(staff(get("/v1/staff/me"), "admin-a-subject")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(A)).andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.authMode").value("OIDC"));
        // Unmapped subject: valid token, no role. Token role/e-mail claims are ignored.
        String unmapped = OIDC.sign(OIDC.claims("someone-else").claim("roles", List.of("ADMIN"))
                .claim("email", "admin@hospital-a.example").claim("tenant", A).build());
        mvc.perform(bearer(get("/v1/staff/me"), unmapped)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STAFF_NOT_AUTHORIZED"));
        // Wrong audience / issuer, expired, unpublished key, HS256, unsigned: all 401.
        Instant now = Instant.now();
        for (String bad : List.of(
                OIDC.sign(OIDC.claims("admin-a-subject").audience("other-api").build()),
                OIDC.sign(OIDC.claims("admin-a-subject").issuer("https://evil.example").build()),
                OIDC.sign(OIDC.claims("admin-a-subject").issueTime(java.util.Date.from(now.minusSeconds(900)))
                        .expirationTime(java.util.Date.from(now.minusSeconds(300))).build()),
                OIDC.sign(OIDC.claims("admin-a-subject").expirationTime(java.util.Date.from(now.plusSeconds(
                        3 * 86400))).build()),
                OIDC.signWithUnpublishedKey(OIDC.claims("admin-a-subject").build()),
                TestOidc.hmac(OIDC.claims("admin-a-subject").build()))) {
            mvc.perform(bearer(get("/v1/staff/me"), bad)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("STAFF_TOKEN_INVALID"));
        }
        mvc.perform(bearer(get("/v1/staff/me"), TestOidc.unsigned(OIDC.claims("admin-a-subject").build())))
                .andExpect(status().isUnauthorized());

        // Administration of mappings: tenant-scoped, audited, no self-disable, disabled means no access.
        String created = body(mvc.perform(staff(post("/v1/admin/staff"), "admin-a-subject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issuer\":\"" + TestOidc.ISSUER + "\",\"subject\":\"operator-a\",\"role\":\"ADMIN\","
                                + "\"displayName\":\"Operator A\",\"evidenceRef\":\"evidence://hr/operator-a\"}"))
                .andExpect(status().isCreated()));
        String principalId = JsonPath.read(created, "$.principalId");
        mvc.perform(staff(post("/v1/admin/staff"), "admin-a-subject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issuer\":\"" + TestOidc.ISSUER + "\",\"subject\":\"operator-a\",\"role\":\"ADMIN\","
                                + "\"displayName\":\"Dup\",\"evidenceRef\":\"evidence://x\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STAFF_ALREADY_MAPPED"));
        mvc.perform(staff(post("/v1/admin/staff"), "admin-a-subject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issuer\":\"https://other-idp.example\",\"subject\":\"x\",\"role\":\"ADMIN\","
                                + "\"displayName\":\"X\",\"evidenceRef\":\"evidence://x\"}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(staff(post("/v1/admin/staff"), "admin-a-subject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issuer\":\"" + TestOidc.ISSUER + "\",\"subject\":\"doc\",\"role\":\"DOCTOR\","
                                + "\"displayName\":\"X\",\"evidenceRef\":\"evidence://x\"}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(staff(get("/v1/staff/me"), "operator-a")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(A));
        // Tenant B's admin can neither see nor disable tenant A's mapping.
        String listB = body(mvc.perform(staff(get("/v1/admin/staff"), "admin-b-subject")).andExpect(status().isOk()));
        assertThat(listB).doesNotContain("operator-a");
        mvc.perform(staff(post("/v1/admin/staff/" + principalId + "/disable"), "admin-b-subject")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"not yours\"}"))
                .andExpect(status().isNotFound());
        String me = body(mvc.perform(staff(get("/v1/staff/me"), "admin-a-subject")).andExpect(status().isOk()));
        mvc.perform(staff(post("/v1/admin/staff/" + JsonPath.read(me, "$.principalId") + "/disable"),
                        "admin-a-subject").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"self\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STAFF_SELF_DISABLE"));
        mvc.perform(staff(post("/v1/admin/staff/" + principalId + "/disable"), "admin-a-subject")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"left the team\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));
        mvc.perform(staff(get("/v1/staff/me"), "operator-a")).andExpect(status().isForbidden());
        stop(app);

        // The bootstrap file is read again at the next start: it never re-enables or changes existing mappings.
        Files.writeString(dir.resolve("staff-bootstrap.json"), "[" + staffEntry(A, "admin-a-subject", "Admin A") + ","
                + staffEntry(B, "admin-b-subject", "Admin B") + "," + staffEntry(A, "operator-a", "Operator A") + "]");
        ConfigurableApplicationContext restarted = startProd();
        mvc(restarted).perform(staff(get("/v1/staff/me"), "operator-a")).andExpect(status().isForbidden());
        stop(restarted);
    }

    @Test
    @Order(4)
    void stateSurvivesRestartAndConsumedSecretsStayConsumed() throws Exception {
        ConfigurableApplicationContext first = startProd();
        MockMvc mvc = mvc(first);
        doctor = activate(mvc, reviewedDoctor(mvc));
        assertThat(bao.keyCreates()).isEqualTo(1);
        assertThat(bao.certificatesIssued()).isEqualTo(1);
        String[] challenge = new String[1];
        String token = certificateLogin(mvc, doctor.leaf(), 200, challenge);
        consumedLoginChallenge = challenge[0];
        doctor = new Doctor(doctor.id(), token, doctor.registrationId(), doctor.leaf(), doctor.keyName(),
                doctor.serial());

        // PACS handoff redeemed once, with a nonce that must stay single-use.
        String handoff = JsonPath.read(body(mvc.perform(bearer(post("/v1/doctor/pacs-handoffs"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"audience\":\"" + PACS + "\"}"))
                .andExpect(status().isCreated())), "$.handoffCode");
        usedNonce = nonce();
        String redeemPath = "/v1/integrations/pacs/handoffs/redeem";
        mvc.perform(signed(post(redeemPath), "POST", redeemPath, "{\"handoffCode\":\"" + handoff + "\"}", usedNonce))
                .andExpect(status().isOk()).andExpect(jsonPath("$.doctorId").value(doctor.id()));
        redeemedHandoff = handoff;

        // Idempotent report registration.
        idempotencyKey = "pacs-approval-" + UUID.randomUUID();
        idempotentBody = reportRequest(doctor.id(), "report-restart", 1);
        idempotentResponse = body(createReport(mvc, idempotencyKey, idempotentBody).andExpect(status().isCreated()));
        stop(first);

        ConfigurableApplicationContext second = startProd();
        MockMvc after = mvc(second);
        // Same session, same doctor identity and credential; no new key or certificate was minted.
        after.perform(bearer(get("/v1/doctor-activation/me"), token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.doctor.status").value("ACTIVE"))
                .andExpect(jsonPath("$.certificate.keyName").value(doctor.keyName()))
                .andExpect(jsonPath("$.certificate.serialNumber").value(doctor.serial()));
        assertThat(bao.keyCreates()).isEqualTo(1);
        assertThat(bao.certificatesIssued()).isEqualTo(1);
        after.perform(bearer(get("/v1/certificate-enrollment/enrollments"), token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].registrationStatus").value("ACTIVE"));
        // Replays after the restart are still refused.
        after.perform(signed(post(redeemPath), "POST", redeemPath, "{\"handoffCode\":\"" + handoff + "\"}",
                usedNonce)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INTEGRATION_NONCE_REPLAYED"));
        after.perform(signed(post(redeemPath), "POST", redeemPath, "{\"handoffCode\":\"" + redeemedHandoff + "\"}",
                nonce())).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("HANDOFF_INVALID"));
        after.perform(post("/v1/certificate-enrollment/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(consumedLoginChallenge, doctor.leaf(), new byte[] {1})))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHALLENGE_ALREADY_USED"));
        String replayed = body(createReport(after, idempotencyKey, idempotentBody).andExpect(status().isCreated()));
        assertThat((String) JsonPath.read(replayed, "$.signRequestId"))
                .isEqualTo(JsonPath.read(idempotentResponse, "$.signRequestId"));
        createReport(after, idempotencyKey, reportRequest(doctor.id(), "report-restart", 2))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        // The request created before the restart is approved and signed after it, exactly once.
        String requestId = JsonPath.read(idempotentResponse, "$.signRequestId");
        String approve = approveBody(after, token, requestId);
        after.perform(bearer(post("/v1/doctor/sign-requests/" + requestId + "/approve"), token)
                        .contentType(MediaType.APPLICATION_JSON).content(approve))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("COMPLETED"));
        after.perform(bearer(post("/v1/doctor/sign-requests/" + requestId + "/approve"), token)
                        .contentType(MediaType.APPLICATION_JSON).content(approve))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NONCE_ALREADY_USED"));
        String path = "/v1/integrations/pacs/report-sign-requests/" + requestId;
        String evidence = body(after.perform(signed(get(path), "GET", path, "", nonce())).andExpect(status().isOk()));
        byte[] snapshot = snapshot(doctor.id(), "report-restart", 1).getBytes(StandardCharsets.UTF_8);
        java.security.Signature verifier = java.security.Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder()
                .decode((String) JsonPath.read(evidence, "$.signature.doctorEvidence.publicKeySpki")))));
        verifier.update(snapshot);
        assertThat(verifier.verify(Base64.getDecoder().decode(
                (String) JsonPath.read(evidence, "$.signature.signatureValueBase64")))).isTrue();
        assertThat((String) JsonPath.read(evidence, "$.signature.signatureMode"))
                .isEqualTo(OpenBaoCredentialProvider.PROD_SIGNATURE_MODE);
        stop(second);
    }

    @Test
    @Order(5)
    void twoInstancesApprovingConcurrentlyProduceExactlyOneSignature() throws Exception {
        ConfigurableApplicationContext one = startProd();
        ConfigurableApplicationContext two = startProd();
        MockMvc mvcOne = mvc(one);
        MockMvc mvcTwo = mvc(two);
        String created = body(createReport(mvcOne, "race-" + UUID.randomUUID(),
                reportRequest(doctor.id(), "report-race", 1)).andExpect(status().isCreated()));
        String requestId = JsonPath.read(created, "$.signRequestId");
        String approve = approveBody(mvcTwo, doctor.token(), requestId);
        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                MockMvc target = i % 2 == 0 ? mvcOne : mvcTwo;
                results.add(pool.submit(() -> {
                    start.await();
                    return target.perform(bearer(post("/v1/doctor/sign-requests/" + requestId + "/approve"),
                                    doctor.token()).contentType(MediaType.APPLICATION_JSON).content(approve))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            int ok = 0;
            for (Future<Integer> f : results) {
                int code = f.get(120, TimeUnit.SECONDS);
                if (code == 202) {
                    ok++;
                } else {
                    assertThat(code).isIn(409);
                }
            }
            assertThat(ok).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(countSql("SELECT count(*) FROM certi_rt.signatures WHERE sign_request_id = ?", requestId))
                .isEqualTo(1);
        assertThat(bao.signBodies).hasSizeGreaterThanOrEqualTo(1);
        // Per-tenant audit chain: contiguous sequence and every event linked to its predecessor's hash.
        try (Connection c = db.adminConnect(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT payload->>'sequence', payload->>'previousHash', payload->>'hash'"
                        + " FROM certi_rt.audit_events WHERE tenant_id = '" + A + "' ORDER BY (payload->>'sequence')::bigint")) {
            long expected = 1;
            String previous = "0".repeat(64);
            while (rs.next()) {
                assertThat(rs.getLong(1)).isEqualTo(expected++);
                assertThat(rs.getString(2)).isEqualTo(previous);
                previous = rs.getString(3);
            }
            assertThat(expected).isGreaterThan(10);
        }
        stop(one);
        stop(two);
    }

    @Test
    @Order(6)
    void tenantsAreIsolated() throws Exception {
        ConfigurableApplicationContext app = startProd();
        MockMvc mvc = mvc(app);
        mvc.perform(staff(get("/v1/admin/doctors/" + doctor.id()), "admin-b-subject"))
                .andExpect(status().isNotFound());
        String doctorsB = body(mvc.perform(staff(get("/v1/admin/doctors"), "admin-b-subject"))
                .andExpect(status().isOk()));
        assertThat(doctorsB).doesNotContain(doctor.id());
        String requestsB = body(mvc.perform(staff(get("/v1/sign-requests"), "admin-b-subject"))
                .andExpect(status().isOk()));
        assertThat(requestsB).doesNotContain(doctor.id());
        String auditB = body(mvc.perform(staff(get("/v1/audit-events"), "admin-b-subject")).andExpect(status().isOk()));
        assertThat(auditB).doesNotContain(doctor.id());
        // Staff of tenant A uploads a PDF; tenant B cannot read it or create a request for it.
        String upload = body(mvc.perform(staff(multipart("/v1/documents"), "admin-a-subject")
                        .file(new MockMultipartFile("file", "r.pdf", "application/pdf",
                                "%PDF-1.7 prod runtime".getBytes(StandardCharsets.US_ASCII)))
                        .file(new MockMultipartFile("metadata", "", "application/json",
                                ("{\"externalDocumentId\":\"doc-iso\",\"externalStudyId\":\"study-iso\",\"title\":\"t\","
                                        + "\"signerId\":\"" + doctor.id() + "\"}").getBytes(StandardCharsets.UTF_8)))
                        .header("Idempotency-Key", "upload-" + UUID.randomUUID()))
                .andExpect(status().isCreated()));
        String documentId = JsonPath.read(upload, "$.id");
        mvc.perform(staff(get("/v1/documents/" + documentId), "admin-b-subject")).andExpect(status().isNotFound());
        mvc.perform(staff(get("/v1/documents/" + documentId), "admin-a-subject")).andExpect(status().isOk());
        // Staff can never approve or sign (the legacy route refuses administrators).
        String request = body(mvc.perform(staff(post("/v1/sign-requests"), "admin-a-subject")
                        .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "req-" + UUID.randomUUID())
                        .content("{\"documentVersionId\":\"" + JsonPath.read(upload, "$.latestVersion.id")
                                + "\",\"signerId\":\"" + doctor.id() + "\"}"))
                .andExpect(status().isCreated()));
        mvc.perform(staff(post("/v1/sign-requests/" + JsonPath.read(request, "$.id") + "/approve"), "admin-a-subject")
                        .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "ap-" + UUID.randomUUID())
                        .content("{\"documentSha256\":\"" + "0".repeat(64) + "\",\"nonce\":\"y\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIGNING_NOT_PERMITTED_FOR_ROLE"));
        stop(app);
    }

    @Test
    @Order(7)
    void tamperingIsRejectedAndCertificateRevocationStopsSigning() throws Exception {
        ConfigurableApplicationContext app = startProd();
        MockMvc mvc = mvc(app);
        String created = body(createReport(mvc, "tamper-" + UUID.randomUUID(),
                reportRequest(doctor.id(), "report-tamper", 1)).andExpect(status().isCreated()));
        String requestId = JsonPath.read(created, "$.signRequestId");
        // Stored bytes and signatures cannot be changed even by the schema owner (trigger); the app login has no
        // UPDATE/DELETE grant on them at all.
        assertThat(countSql("SELECT count(*) FROM certi_rt.blobs")).isPositive();
        assertThat(countSql("SELECT count(*) FROM certi_rt.signatures")).isPositive();
        try (Connection c = db.adminConnect(); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate("UPDATE certi_rt.blobs SET content = 'x'::bytea"))
                    .hasMessageContaining("immutable record");
        }
        try (Connection c = db.adminConnect(); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate("DELETE FROM certi_rt.signatures"))
                    .hasMessageContaining("immutable record");
        }
        try (Connection c = db.connect(); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate("UPDATE certi_rt.signatures SET payload = payload"))
                    .isInstanceOf(SQLException.class);
        }
        // A sign request whose stored hash is altered behind the service's back is never signed.
        String forged = "f".repeat(64);
        try (Connection c = db.adminConnect(); PreparedStatement ps = c.prepareStatement(
                "UPDATE certi_rt.sign_requests SET payload = jsonb_set(payload, '{documentSha256}', to_jsonb(?::text))"
                        + " WHERE id = ?::uuid")) {
            ps.setString(1, forged);
            ps.setString(2, requestId);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
        String request = body(mvc.perform(bearer(get("/v1/doctor/sign-requests/" + requestId), doctor.token()))
                .andExpect(status().isOk()));
        mvc.perform(bearer(post("/v1/doctor/sign-requests/" + requestId + "/approve"), doctor.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentSha256\":\"" + forged + "\",\"nonce\":\"" + JsonPath.read(request, "$.nonce")
                                + "\",\"reviewAttestation\":\"REVIEWED_EXACT_REPORT_CONTENT\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("STORED_DOCUMENT_CORRUPTED"));
        assertThat(countSql("SELECT count(*) FROM certi_rt.signatures WHERE sign_request_id = ?", requestId))
                .isZero();

        // Prepare approval while authorized. After revocation even reading a new approval nonce is denied.
        String next = body(createReport(mvc, "revoked-" + UUID.randomUUID(),
                reportRequest(doctor.id(), "report-revoked", 1)).andExpect(status().isCreated()));
        String nextId = JsonPath.read(next, "$.signRequestId");
        String beforeRevocation = approveBody(mvc, doctor.token(), nextId);
        writeCrls(doctor.leaf().certificate());
        mvc.perform(bearer(post("/v1/doctor/sign-requests/" + nextId + "/approve"), doctor.token())
                        .contentType(MediaType.APPLICATION_JSON).content(beforeRevocation))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_ENROLLMENT_NOT_VALID"));
        mvc.perform(bearer(get("/v1/doctor/sign-requests"), doctor.token())).andExpect(status().isForbidden());
        mvc.perform(bearer(get("/v1/doctor/sign-requests/" + nextId + "/document"), doctor.token()))
                .andExpect(status().isForbidden());
        String enrollments = body(mvc.perform(staff(get("/v1/admin/doctors/" + doctor.id()
                + "/certificate-enrollments"), "admin-a-subject")).andExpect(status().isOk()));
        assertThat((String) JsonPath.read(enrollments, "$.items[0].registrationStatus")).isEqualTo("REVOKED");
        assertThat((String) JsonPath.read(enrollments, "$.items[0].revocationReason")).isEqualTo("EXTERNAL_REVOKED");
        certificateLogin(mvc, doctor.leaf(), 403, null);
        assertThat(countSql("SELECT count(*) FROM certi_rt.signatures WHERE sign_request_id = ?", nextId)).isZero();
        writeCrls();
        stop(app);
    }

    @Test
    @Order(8)
    void interruptedActivationIsRecoveredWithTheSameOpenBaoKey() throws Exception {
        ConfigurableApplicationContext app = startProd();
        MockMvc mvc = mvc(app);
        Doctor reviewed = reviewedDoctor(mvc);
        // Simulate an instance that created the Transit key and then crashed before recording the credential.
        String keyName = "vc-doctor-" + reviewed.id();
        HttpClient http = HttpClient.newBuilder().sslContext(trusting(bao.tlsCaFile())).build();
        HttpResponse<String> createKey = http.send(HttpRequest.newBuilder(URI.create(bao.addr()
                        + "/v1/transit/keys/" + keyName)).header("X-Vault-Token", FakeOpenBao.provisioningToken())
                .POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"ecdsa-p256\",\"exportable\":false,"
                        + "\"allow_plaintext_backup\":false}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(createKey.statusCode()).isLessThan(300);
        String keyInfo = http.send(HttpRequest.newBuilder(URI.create(bao.addr() + "/v1/transit/keys/" + keyName))
                .header("X-Vault-Token", FakeOpenBao.provisioningToken()).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        int keysBefore = bao.keyCreates();
        try (Connection c = db.adminConnect(); PreparedStatement ps = c.prepareStatement(
                "UPDATE certi_rt.doctors SET payload = payload || jsonb_build_object('activationInProgress', true,"
                        + " 'activationStartedAt', ?::text) WHERE id = ?::uuid")) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, reviewed.id());
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
        // While the other attempt's lease is fresh, a second activation is refused...
        mvc.perform(bearer(post("/v1/doctor-activation/activate"), reviewed.token()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACTIVATION_IN_PROGRESS"));
        try (Connection c = db.adminConnect(); PreparedStatement ps = c.prepareStatement(
                "UPDATE certi_rt.doctors SET payload = payload || jsonb_build_object('activationStartedAt', ?::text)"
                        + " WHERE id = ?::uuid")) {
            ps.setString(1, Instant.now().minusSeconds(600).toString());
            ps.setString(2, reviewed.id());
            ps.executeUpdate();
        }
        // ...and after it expires the retry completes with the existing key: no second signing identity.
        Doctor active = activate(mvc, reviewed);
        assertThat(active.keyName()).isEqualTo(keyName);
        assertThat(bao.keyCreates()).isEqualTo(keysBefore);
        String me = body(mvc.perform(bearer(get("/v1/doctor-activation/me"), reviewed.token()))
                .andExpect(status().isOk()));
        String publicKeyPem = JsonPath.read(keyInfo, "$.data.keys.1.public_key");
        byte[] transitSpki = Base64.getMimeDecoder().decode(publicKeyPem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "").strip());
        assertThat((String) JsonPath.read(me, "$.certificate.publicKeySha256"))
                .isEqualTo(Hashing.sha256Hex(transitSpki));
        stop(app);
    }

    private static SSLContext trusting(Path caFile) throws Exception {
        X509Certificate ca = (X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(Files.newInputStream(caFile));
        java.security.KeyStore store = java.security.KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setCertificateEntry("ca", ca);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, tmf.getTrustManagers(), null);
        return context;
    }
}
