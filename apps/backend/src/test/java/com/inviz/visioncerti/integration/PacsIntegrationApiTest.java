package com.inviz.visioncerti.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inviz.visioncerti.certificate.TestPki;
import com.inviz.visioncerti.common.Hashing;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.cert.X509CRL;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * SV70 PACS integration over HTTP: HMAC service authentication, the certificate-login handoff, and report snapshot
 * signing with the physician's explicit approval at Vision-Certi. Synthetic PKI and DEV_SIMULATED keys only.
 */
@SpringBootTest(properties = {
        "visioncerti.doctor.admin-invitations-enabled=true",
        "visioncerti.doctor.signing-backend=DEV_SIMULATED",
        "visioncerti.pacs.client-id=" + PacsIntegrationApiTest.CLIENT,
        "visioncerti.pacs.client-secret=" + PacsIntegrationApiTest.SECRET,
        "visioncerti.pacs.tenant-ids=demo-hospital-a"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class PacsIntegrationApiTest {

    static final String CLIENT = "pacs-sv70";
    static final String SECRET = "test-only-secret-0123456789abcdefghijklmnop";
    static final String AUDIENCE = IntegrationClients.DEFAULT_AUDIENCE;
    static final TestPki PKI = new TestPki(Clock.systemUTC(), "Pacs");
    private static final AtomicInteger SEQ = new AtomicInteger(5000);
    private static final String A = "demo-hospital-a";
    private static final String CONSENT =
            "{\"version\":\"2026-10-13-draft\",\"authentication\":true,\"signature\":true,\"privacy\":true}";

    @DynamicPropertySource
    static void trustFiles(DynamicPropertyRegistry registry) throws Exception {
        Path dir = Files.createTempDirectory("vc-pacs-test");
        Path anchors = dir.resolve("anchors.pem");
        Files.writeString(anchors, TestPki.pem(PKI.root.certificate()));
        List<X509CRL> crls = PKI.freshCrls();
        Path rootCrl = dir.resolve("root.crl");
        Path issuingCrl = dir.resolve("issuing.crl");
        Files.write(rootCrl, crls.get(0).getEncoded());
        Files.write(issuingCrl, crls.get(1).getEncoded());
        registry.add("visioncerti.cert.trust-anchors-file", anchors::toString);
        registry.add("visioncerti.cert.crl-files", () -> rootCrl + "," + issuingCrl);
    }

    @Autowired
    private MockMvc mvc;

    record ActiveDoctor(String id, String token, String registrationId, TestPki.Leaf leaf) {
    }

    // ------------------------------------------------------------------ helpers

    private static final String START = "/v1/integrations/pacs/enrollments/start";
    private static final String STATUS = "/v1/integrations/pacs/enrollments/status";

    private static String enrollmentBody(String user, String license) {
        return "{\"tenantId\":\"" + A + "\",\"pacsUserId\":\"" + user
                + "\",\"name\":\"김판독\",\"licenseNumber\":\"" + license + "\",\"centerName\":\"판독센터\"}";
    }

    @Test
    void pacsSelfEnrollmentRequiresServiceAuthAndCannotTakeExistingLicense() throws Exception {
        String license = Integer.toString(SEQ.incrementAndGet());
        String user = "first-" + license;
        String body = enrollmentBody(user, license);
        mvc.perform(post(START).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        servicePost(START, body.replace(A, "other-hospital")).andExpect(status().isForbidden());
        servicePost(START, body).andExpect(status().isCreated());
        servicePost(START, enrollmentBody("other-" + license, license)).andExpect(status().isConflict());
        servicePost(START, enrollmentBody(user, Integer.toString(SEQ.incrementAndGet())))
                .andExpect(status().isConflict());
        // Null Google email is not an invented shared address and must not collide across doctors.
        servicePost(START, enrollmentBody("independent-" + SEQ.incrementAndGet(), Integer.toString(SEQ.get())))
                .andExpect(status().isCreated());
        mvc.perform(post("/v1/certificate-enrollment/challenges").contentType(MediaType.APPLICATION_JSON)
                .content("{\"doctorId\":\"00000000-0000-0000-0000-000000000001\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void pacsFirstEnrollmentUsesRealProofConsentReviewAndKeepsDurableAccountBinding() throws Exception {
        String license = Integer.toString(SEQ.incrementAndGet());
        String user = "flow-" + license;
        String query = "{\"tenantId\":\"" + A + "\",\"pacsUserId\":\"" + user + "\"}";
        servicePost(STATUS, query).andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationStatus").value("NOT_REGISTERED"));
        String start = servicePost(START, enrollmentBody(user, license)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String doctorId = JsonPath.read(start, "$.doctorId");
        servicePost(START, enrollmentBody(user, license)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.doctorId").value(doctorId));
        mvc.perform(admin(get("/v1/admin/doctors/" + doctorId + "/evidence")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        byte[] payload = Base64.getDecoder().decode((String) JsonPath.read(start, "$.challenge.signedPayloadBase64"));
        TestPki.Leaf leaf = PKI.leaf(TestPki.rsa(2048));
        String proof = "{\"registrationId\":\"" + JsonPath.read(start, "$.challenge.registrationId")
                + "\",\"certificate\":\"" + leaf.der() + "\",\"chain\":[\"" + PKI.intermediateDer()
                + "\"],\"signature\":\"" + TestPki.sign(leaf.keys(), "SHA256withRSA", payload)
                + "\",\"signatureAlgorithm\":\"SHA256withRSA\"}";
        String verified = mvc.perform(post("/v1/certificate-enrollment/verify")
                .contentType(MediaType.APPLICATION_JSON).content(proof)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.enrollment.reviewStatus").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/v1/certificate-enrollment/verify").contentType(MediaType.APPLICATION_JSON).content(proof))
                .andExpect(status().isConflict());
        String token = JsonPath.read(verified, "$.activationToken");
        String registration = JsonPath.read(verified, "$.enrollment.registrationId");
        mvc.perform(bearer(post("/v1/certificate-enrollment/enrollments/" + registration + "/consent"), token)
                .contentType(MediaType.APPLICATION_JSON).content(CONSENT)).andExpect(status().isOk());
        servicePost(STATUS, query).andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationStatus").value("PENDING"));
        mvc.perform(bearer(post("/v1/doctor-activation/activate"), token)).andExpect(status().isConflict());
        mvc.perform(admin(post("/v1/admin/doctors/" + doctorId + "/certificate-reviews"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"registrationId\":\"" + registration
                        + "\",\"decision\":\"APPROVE\",\"fingerprintSha256\":\"" + leaf.fingerprint()
                        + "\",\"evidenceRef\":\"evidence://verified-identity-and-license\"}"))
                .andExpect(status().isOk());
        mvc.perform(bearer(post("/v1/doctor-activation/activate"), token)).andExpect(status().isOk());
        servicePost(STATUS, query).andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.certificateEnrollmentId").value(registration))
                .andExpect(jsonPath("$.fingerprintSha256").value(leaf.fingerprint()))
                .andExpect(jsonPath("$.doctorLicenseNumber").value(license));
        servicePost(START, enrollmentBody(user, license)).andExpect(status().isConflict());
        mvc.perform(admin(patch("/v1/admin/doctors/" + doctorId + "/status"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"REVOKED\",\"reason\":\"test revoked\"}"))
                .andExpect(status().isOk());
        servicePost(STATUS, query).andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationStatus").value("REVOKED"));
        servicePost(START, enrollmentBody(user, license)).andExpect(status().isForbidden());
        servicePost(START, enrollmentBody("replacement-" + user, license)).andExpect(status().isConflict());
    }

    private static <T extends AbstractMockHttpServletRequestBuilder<?>> T admin(T b) {
        b.header("X-Tenant-Id", A).header("X-Actor-Id", "admin-demo");
        return b;
    }

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", "Bearer " + token);
    }

    /** Signs an integration request exactly like the PACS client does. */
    static MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder b, String method, String path,
            String body, String nonce, long timestamp, String secret) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String sha = Hashing.sha256Hex(bytes);
        String ts = Long.toString(timestamp);
        String signature = IntegrationAuthFilter.sign(secret.getBytes(StandardCharsets.UTF_8),
                IntegrationAuthFilter.canonical(AUDIENCE, CLIENT, method, path, null, ts, nonce, sha));
        return b.header("X-VC-Client-Id", CLIENT).header("X-VC-Timestamp", ts).header("X-VC-Nonce", nonce)
                .header("X-VC-Content-SHA256", sha).header("X-VC-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON).content(bytes);
    }

    private static String nonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private ResultActions servicePost(String path, String body) throws Exception {
        return mvc.perform(signed(post(path), "POST", path, body, nonce(), Instant.now().getEpochSecond(), SECRET));
    }

    private ResultActions serviceGet(String path) throws Exception {
        return mvc.perform(signed(get(path), "GET", path, "", nonce(), Instant.now().getEpochSecond(), SECRET));
    }

    private String certificateLogin(TestPki.Leaf leaf) throws Exception {
        String login = mvc.perform(post("/v1/certificate-enrollment/login-challenges"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        byte[] payload = Base64.getDecoder().decode((String) JsonPath.read(login, "$.signedPayloadBase64"));
        String session = mvc.perform(post("/v1/certificate-enrollment/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + JsonPath.read(login, "$.challengeId")
                                + "\",\"fingerprintSha256\":\"" + leaf.fingerprint() + "\",\"certificate\":\""
                                + leaf.der() + "\",\"chain\":[\"" + PKI.intermediateDer() + "\"],\"signature\":\""
                                + TestPki.sign(leaf.keys(), "SHA256withRSA", payload)
                                + "\",\"signatureAlgorithm\":\"SHA256withRSA\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(session, "$.activationToken");
    }

    /** Preregistration → certificate proof → consent → admin review → activation; returns a fresh login session. */
    private ActiveDoctor activeDoctor() throws Exception {
        String pre = mvc.perform(admin(post("/v1/admin/doctors")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"박판독\",\"licenseNumber\":\"" + SEQ.incrementAndGet()
                                + "\",\"centerName\":\"서울영상센터\",\"googleEmail\":\"p" + SEQ.get()
                                + "@example.com\",\"identityEvidenceRef\":\"evidence://id\","
                                + "\"licenseEvidenceRef\":\"evidence://lic\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String doctorId = JsonPath.read(pre, "$.doctor.id");
        String challenge = mvc.perform(post("/v1/certificate-enrollment/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invitationCode\":\"" + JsonPath.read(pre, "$.invitationCode") + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        TestPki.Leaf leaf = PKI.leaf(TestPki.rsa(2048));
        byte[] payload = Base64.getDecoder().decode((String) JsonPath.read(challenge, "$.signedPayloadBase64"));
        String session = mvc.perform(post("/v1/certificate-enrollment/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationId\":\"" + JsonPath.read(challenge, "$.registrationId")
                                + "\",\"certificate\":\"" + leaf.der() + "\",\"chain\":[\"" + PKI.intermediateDer()
                                + "\"],\"signature\":\"" + TestPki.sign(leaf.keys(), "SHA256withRSA", payload)
                                + "\",\"signatureAlgorithm\":\"SHA256withRSA\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(session, "$.activationToken");
        String registrationId = JsonPath.read(session, "$.enrollment.registrationId");
        mvc.perform(bearer(post("/v1/certificate-enrollment/enrollments/" + registrationId + "/consent"), token)
                .contentType(MediaType.APPLICATION_JSON).content(CONSENT)).andExpect(status().isOk());
        mvc.perform(admin(post("/v1/admin/doctors/" + doctorId + "/certificate-reviews"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationId\":\"" + registrationId + "\",\"decision\":\"APPROVE\","
                                + "\"fingerprintSha256\":\"" + leaf.fingerprint()
                                + "\",\"evidenceRef\":\"evidence://license-scan\"}"))
                .andExpect(status().isOk());
        mvc.perform(bearer(post("/v1/doctor-activation/activate"), token)).andExpect(status().isOk());
        return new ActiveDoctor(doctorId, certificateLogin(leaf), registrationId, leaf);
    }

    private String handoff(String token) throws Exception {
        String body = mvc.perform(bearer(post("/v1/doctor/pacs-handoffs"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"audience\":\"" + CLIENT + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.audience").value(CLIENT))
                .andExpect(jsonPath("$.tenantId").value(A))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.handoffCode");
    }

    static String snapshot(String tenantId, String doctorId, String reportId, int revision, String reading) {
        return "{\"doctorId\":\"" + doctorId + "\",\"report\":{\"conclusion\":\"no acute\",\"externalReportId\":\""
                + reportId + "\",\"reading\":\"" + reading + "\",\"revision\":" + revision
                + "},\"schema\":\"saintview-pacs-report-snapshot/v1\",\"tenantId\":\"" + tenantId + "\"}";
    }

    static String createBody(String tenantId, String doctorId, String reportId, int revision, String snapshot,
            String sha) {
        return "{\"tenantId\":\"" + tenantId + "\",\"doctorId\":\"" + doctorId + "\",\"externalReportId\":\""
                + reportId + "\",\"externalStudyId\":\"pacs-sv70:study:9\",\"revision\":" + revision
                + ",\"title\":\"CT report rev " + revision + "\",\"contentType\":\""
                + "application/vnd.saintview.pacs-report+json;version=1\",\"contentBase64\":\""
                + Base64.getEncoder().encodeToString(snapshot.getBytes(StandardCharsets.UTF_8))
                + "\",\"contentSha256\":\"" + sha + "\"}";
    }

    private ResultActions createSignRequest(String key, String body) throws Exception {
        String path = "/v1/integrations/pacs/report-sign-requests";
        return mvc.perform(signed(post(path), "POST", path, body, nonce(), Instant.now().getEpochSecond(), SECRET)
                .header("Idempotency-Key", key));
    }

    // ------------------------------------------------------------------ tests

    @Test
    void hmacMatchesTheSharedCrossLanguageVector() {
        // Same vector is asserted by the PACS (Python) client tests.
        String canonical = IntegrationAuthFilter.canonical("vision-certi:pacs-integration:v1", "pacs-sv70", "POST",
                "/v1/integrations/pacs/handoffs/redeem", null, "1760000000", "AAAAAAAAAAAAAAAAAAAAAA",
                Hashing.sha256Hex("{\"handoffCode\":\"x\"}".getBytes(StandardCharsets.UTF_8)));
        assertThat(IntegrationAuthFilter.sign("vector-secret-vector-secret-vector-secret".getBytes(
                StandardCharsets.UTF_8), canonical)).isEqualTo(CrossLanguageVector.EXPECTED_SIGNATURE);
    }

    @Test
    void serviceAuthenticationRejectsEverythingButAFreshCorrectlySignedRequest() throws Exception {
        String path = "/v1/integrations/pacs/handoffs/redeem";
        String body = "{\"handoffCode\":\"" + "A".repeat(43) + "\"}";
        long now = Instant.now().getEpochSecond();
        // No headers / dev headers / doctor bearer are never accepted.
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INTEGRATION_AUTH_INVALID"));
        mvc.perform(admin(post(path)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INTEGRATION_AUTH_INVALID"));
        // Wrong secret, stale timestamp, tampered body.
        mvc.perform(signed(post(path), "POST", path, body, nonce(), now, SECRET + "x"))
                .andExpect(status().isUnauthorized());
        mvc.perform(signed(post(path), "POST", path, body, nonce(), now - 3600, SECRET))
                .andExpect(status().isUnauthorized());
        mvc.perform(signed(post(path), "POST", path, body, nonce(), now, SECRET)
                        .content("{\"handoffCode\":\"" + "B".repeat(43) + "\"}"))
                .andExpect(status().isUnauthorized());
        // Signed for another path.
        mvc.perform(signed(post(path), "POST", "/v1/integrations/pacs/report-sign-requests", body, nonce(), now,
                SECRET)).andExpect(status().isUnauthorized());
        // Correct signature → reaches the service (unknown code → 401 HANDOFF_INVALID); replay of its nonce → 409.
        String n = nonce();
        mvc.perform(signed(post(path), "POST", path, body, n, now, SECRET))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("HANDOFF_INVALID"));
        mvc.perform(signed(post(path), "POST", path, body, n, now, SECRET))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INTEGRATION_NONCE_REPLAYED"));
    }

    @Test
    void handoffNeedsAFreshCertificateSessionIsAudienceBoundAndSingleUse() throws Exception {
        ActiveDoctor doctor = activeDoctor();
        mvc.perform(admin(post("/v1/doctor/pacs-handoffs")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"audience\":\"" + CLIENT + "\"}")).andExpect(status().isUnauthorized());
        mvc.perform(bearer(post("/v1/doctor/pacs-handoffs"), doctor.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"audience\":\"other-pacs\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("HANDOFF_AUDIENCE_INVALID"));

        String code = handoff(doctor.token());
        String redeem = "/v1/integrations/pacs/handoffs/redeem";
        servicePost(redeem, "{\"handoffCode\":\"" + code + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(A))
                .andExpect(jsonPath("$.doctorId").value(doctor.id()))
                .andExpect(jsonPath("$.identityMode").value("PC_CERTIFICATE"))
                .andExpect(jsonPath("$.certificateEnrollmentId").value(doctor.registrationId()))
                .andExpect(jsonPath("$.certificateFingerprintSha256").value(doctor.leaf().fingerprint()))
                .andExpect(jsonPath("$.activeSigner").value(true))
                // The admin-registered license, for PACS to cross-check its local account (never an authenticator).
                .andExpect(jsonPath("$.doctorLicenseNumber").value(org.hamcrest.Matchers.matchesPattern("[0-9]{1,20}")))
                .andExpect(jsonPath("$.handoffCode").doesNotExist());
        // Single use.
        servicePost(redeem, "{\"handoffCode\":\"" + code + "\"}")
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("HANDOFF_INVALID"));

        // A code issued before unregister cannot be redeemed afterwards; no new code can be issued.
        String early = handoff(doctor.token());
        mvc.perform(bearer(post("/v1/certificate-enrollment/enrollments/" + doctor.registrationId() + "/unregister"),
                        doctor.token()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"lost\"}"))
                .andExpect(status().isOk());
        servicePost(redeem, "{\"handoffCode\":\"" + early + "\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("HANDOFF_IDENTITY_NOT_VALID"));
        mvc.perform(bearer(post("/v1/doctor/pacs-handoffs"), doctor.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"audience\":\"" + CLIENT + "\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void reportSnapshotIsSignedOnlyAfterTheDoctorsExplicitApprovalAndEvidenceIsReturned() throws Exception {
        ActiveDoctor doctor = activeDoctor();
        String reportId = "pacs-sv70:report:" + SEQ.incrementAndGet();
        String snap = snapshot(A, doctor.id(), reportId, 1, "No focal lesion.");
        byte[] snapBytes = snap.getBytes(StandardCharsets.UTF_8);
        String sha = Hashing.sha256Hex(snapBytes);
        String body = createBody(A, doctor.id(), reportId, 1, snap, sha);
        String key = "pacs-approval-" + UUID.randomUUID();

        // Validation: idempotency key, hash, snapshot binding, tenant scope.
        String path = "/v1/integrations/pacs/report-sign-requests";
        servicePost(path, body).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        createSignRequest("k-" + UUID.randomUUID(), createBody(A, doctor.id(), reportId, 1, snap, "0".repeat(64)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOCUMENT_HASH_MISMATCH"));
        String foreignSnap = snapshot(A, UUID.randomUUID().toString(), reportId, 1, "x");
        createSignRequest("k-" + UUID.randomUUID(), createBody(A, doctor.id(), reportId, 1, foreignSnap,
                Hashing.sha256Hex(foreignSnap.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REPORT_SNAPSHOT_INVALID"));
        createSignRequest("k-" + UUID.randomUUID(), createBody("demo-hospital-b", doctor.id(), reportId, 1, snap, sha))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INTEGRATION_TENANT_FORBIDDEN"));

        String created = createSignRequest(key, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentSha256").value(sha))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.nonce").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String requestId = JsonPath.read(created, "$.signRequestId");
        // Idempotent replay; same key with another body is refused.
        createSignRequest(key, body).andExpect(jsonPath("$.signRequestId").value(requestId));
        createSignRequest(key, createBody(A, doctor.id(), reportId, 1, snap.replace("No focal", "Focal"),
                Hashing.sha256Hex(snap.replace("No focal", "Focal").getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        serviceGet(path + "/" + requestId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.signature").doesNotExist())
                .andExpect(jsonPath("$.nonce").doesNotExist());

        // The physician reads the exact bytes (not a PDF) and must attest the review explicitly.
        mvc.perform(bearer(get("/v1/doctor/sign-requests/" + requestId + "/document"), doctor.token()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/vnd.saintview.pacs-report+json;version=1"))
                .andExpect(header().string("X-Document-Sha256", sha))
                .andExpect(content().bytes(snapBytes));
        String nonce = JsonPath.read(mvc.perform(bearer(get("/v1/doctor/sign-requests/" + requestId), doctor.token()))
                .andReturn().getResponse().getContentAsString(), "$.nonce");
        mvc.perform(bearer(post("/v1/doctor/sign-requests/" + requestId + "/approve"), doctor.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentSha256\":\"" + sha + "\",\"nonce\":\"" + nonce + "\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REVIEW_ATTESTATION_REQUIRED"));
        mvc.perform(bearer(post("/v1/doctor/sign-requests/" + requestId + "/approve"), doctor.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentSha256\":\"" + sha + "\",\"nonce\":\"" + nonce
                                + "\",\"reviewAttestation\":\"REVIEWED_EXACT_REPORT_CONTENT\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        String status = serviceGet(path + "/" + requestId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.signature.documentSha256").value(sha))
                .andExpect(jsonPath("$.signature.algorithm").value("ECDSA_P256_SHA256"))
                .andExpect(jsonPath("$.signature.doctorEvidence.doctorId").value(doctor.id()))
                .andExpect(jsonPath("$.signature.doctorEvidence.certificateEnrollmentId")
                        .value(doctor.registrationId()))
                .andExpect(jsonPath("$.signature.doctorEvidence.certificateEnrollmentFingerprint")
                        .value(doctor.leaf().fingerprint()))
                .andReturn().getResponse().getContentAsString();
        // The evidence verifies over the exact snapshot bytes with the recorded key.
        java.security.Signature verifier = java.security.Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder()
                .decode((String) JsonPath.read(status, "$.signature.doctorEvidence.publicKeySpki")))));
        verifier.update(snapBytes);
        assertThat(verifier.verify(Base64.getDecoder()
                .decode((String) JsonPath.read(status, "$.signature.signatureValueBase64")))).isTrue();

        // A new revision appends a new immutable version and a new request.
        String snap2 = snapshot(A, doctor.id(), reportId, 2, "Small nodule, follow-up.");
        String sha2 = Hashing.sha256Hex(snap2.getBytes(StandardCharsets.UTF_8));
        String created2 = createSignRequest("pacs-approval-" + UUID.randomUUID(),
                createBody(A, doctor.id(), reportId, 2, snap2, sha2))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(created2, "$.signRequestId")).isNotEqualTo(requestId);
        assertThat((String) JsonPath.read(created2, "$.documentVersionId"))
                .isNotEqualTo(JsonPath.read(created, "$.documentVersionId"));
    }

    @Test
    void serviceCannotSeeRequestsItDidNotCreate() throws Exception {
        ActiveDoctor doctor = activeDoctor();
        byte[] pdf = ("%PDF-1.7\nreport " + UUID.randomUUID() + "\n%%EOF").getBytes(StandardCharsets.US_ASCII);
        String metadata = "{\"externalDocumentId\":\"pdf-" + UUID.randomUUID()
                + "\",\"externalStudyId\":\"s\",\"title\":\"t\",\"signerId\":\"" + doctor.id() + "\"}";
        String doc = mvc.perform(admin(multipart("/v1/documents"))
                        .file(new MockMultipartFile("metadata", "", MediaType.APPLICATION_JSON_VALUE,
                                metadata.getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("file", "r.pdf", MediaType.APPLICATION_PDF_VALUE, pdf))
                        .header("Idempotency-Key", "doc-" + UUID.randomUUID()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String request = mvc.perform(admin(post("/v1/sign-requests")).contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "req-" + UUID.randomUUID())
                        .content("{\"documentVersionId\":\"" + JsonPath.read(doc, "$.latestVersion.id")
                                + "\",\"signerId\":\"" + doctor.id() + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        serviceGet("/v1/integrations/pacs/report-sign-requests/" + JsonPath.read(request, "$.id"))
                .andExpect(status().isNotFound());
        // PDFs keep their PDF content type for the physician.
        mvc.perform(bearer(get("/v1/doctor/sign-requests/" + JsonPath.read(request, "$.id") + "/document"),
                doctor.token())).andExpect(header().string("Content-Type", "application/pdf"));
    }
}
