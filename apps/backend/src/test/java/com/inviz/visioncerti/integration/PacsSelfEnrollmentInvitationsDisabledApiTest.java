package com.inviz.visioncerti.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * Disabling administrator invitations preserves real certificate proof, consent/review, login and session renewal.
 * Uses synthetic PKI and DEV_SIMULATED signing keys only.
 */
@SpringBootTest(properties = {
        "visioncerti.doctor.admin-invitations-enabled=false",
        "visioncerti.doctor.signing-backend=DEV_SIMULATED",
        "visioncerti.pacs.client-id=" + PacsSelfEnrollmentInvitationsDisabledApiTest.CLIENT,
        "visioncerti.pacs.client-secret=" + PacsSelfEnrollmentInvitationsDisabledApiTest.SECRET,
        "visioncerti.pacs.tenant-ids=demo-hospital-a"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class PacsSelfEnrollmentInvitationsDisabledApiTest {

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
    void selfEnrollmentCertificateLoginRenewalAndManagementStillWorkWithoutInvitations() throws Exception {
        mvc.perform(get("/v1/doctor-activation/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.adminInvitationsEnabled").value(false));
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
                .contentType(MediaType.APPLICATION_JSON).content(CONSENT)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEDICAL_CREDENTIALS_REQUIRED"));
        String medical = mvc.perform(bearer(put("/v1/certificate-enrollment/enrollments/" + registration + "/medical-credentials"), token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"medicalLicenseNumber\":\"" + license + "\",\"specialist\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.medicalCredentialsRequired").value(true))
                .andExpect(jsonPath("$.reviewStatus").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String medicalSubmittedAt = JsonPath.read(medical, "$.medicalCredentialsSubmittedAt");
        mvc.perform(bearer(post("/v1/certificate-enrollment/enrollments/" + registration + "/consent"), token)
                .contentType(MediaType.APPLICATION_JSON).content(CONSENT)).andExpect(status().isOk());
        servicePost(STATUS, query).andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationStatus").value("PENDING"));
        mvc.perform(bearer(post("/v1/doctor-activation/activate"), token)).andExpect(status().isConflict());
        mvc.perform(admin(post("/v1/admin/doctors/" + doctorId + "/certificate-reviews"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"registrationId\":\"" + registration
                        + "\",\"decision\":\"APPROVE\",\"fingerprintSha256\":\"" + leaf.fingerprint()
                        + "\",\"evidenceRef\":\"evidence://verified-identity-and-license\",\"medicalCredentialsSubmittedAt\":\"" + medicalSubmittedAt + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(bearer(post("/v1/doctor-activation/activate"), token)).andExpect(status().isOk());
        servicePost(STATUS, query).andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.certificateEnrollmentId").value(registration))
                .andExpect(jsonPath("$.fingerprintSha256").value(leaf.fingerprint()))
                .andExpect(jsonPath("$.doctorLicenseNumber").value(license));
        String loginToken = certificateLogin(leaf);
        mvc.perform(bearer(post("/v1/certificate-enrollment/challenges"), loginToken))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.doctorId").value(doctorId));
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

}
