package com.inviz.visioncerti.certificate;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inviz.visioncerti.common.Hashing;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509CRL;
import java.time.Clock;
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
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * HTTP flow under the default CERTIFICATE_REQUIRED policy with trust anchors and CRLs read from files (synthetic PKI
 * generated at test time), DEV_SIMULATED signing keys, and the physician's bearer session only.
 */
@SpringBootTest(properties = {"visioncerti.doctor.signing-backend=DEV_SIMULATED",
        "visioncerti.doctor.admin-invitations-enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CertificateEnrollmentApiTest {

    static final TestPki PKI = new TestPki(Clock.systemUTC(), "Api");
    private static final AtomicInteger SEQ = new AtomicInteger(9000);
    private static final String A = "demo-hospital-a";
    private static final String CONSENT =
            "{\"version\":\"2026-10-13-draft\",\"authentication\":true,\"signature\":true,\"privacy\":true}";

    @DynamicPropertySource
    static void trustFiles(DynamicPropertyRegistry registry) throws Exception {
        Path dir = Files.createTempDirectory("vc-cert-test");
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

    private static <T extends AbstractMockHttpServletRequestBuilder<?>> T admin(T builder) {
        builder.header("X-Tenant-Id", A).header("X-Actor-Id", "admin-demo");
        return builder;
    }

    private static <T extends AbstractMockHttpServletRequestBuilder<?>> T bearer(T builder, String token) {
        builder.header("Authorization", "Bearer " + token);
        return builder;
    }

    /** Returns [doctorId, invitationCode]. */
    private String[] preregister() throws Exception {
        String body = mvc.perform(admin(post("/v1/admin/doctors")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"김의사\",\"licenseNumber\":\"" + SEQ.incrementAndGet()
                                + "\",\"centerName\":\"서울영상센터\",\"googleEmail\":\"c" + SEQ.get()
                                + "@example.com\",\"identityEvidenceRef\":\"evidence://id\","
                                + "\"licenseEvidenceRef\":\"evidence://lic\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new String[] {JsonPath.read(body, "$.doctor.id"), JsonPath.read(body, "$.invitationCode")};
    }

    private String verify(String challengeBody, TestPki.Leaf leaf) throws Exception {
        String registrationId = JsonPath.read(challengeBody, "$.registrationId");
        byte[] payload = Base64.getDecoder().decode((String) JsonPath.read(challengeBody, "$.signedPayloadBase64"));
        return mvc.perform(post("/v1/certificate-enrollment/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationId\":\"" + registrationId + "\",\"certificate\":\"" + leaf.der()
                                + "\",\"chain\":[\"" + PKI.intermediateDer() + "\"],\"signature\":\""
                                + TestPki.sign(leaf.keys(), "SHA256withRSA", payload)
                                + "\",\"signatureAlgorithm\":\"SHA256withRSA\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.identityMode").value("PC_CERTIFICATE"))
                .andExpect(jsonPath("$.enrollment.registrationStatus").value("PENDING"))
                .andExpect(jsonPath("$.enrollment.externalStatus").value("GOOD"))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void configReportsCertificatePolicyAndTrust() throws Exception {
        mvc.perform(get("/v1/certificate-enrollment/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activationPolicy").value("CERTIFICATE_REQUIRED"))
                .andExpect(jsonPath("$.pcCertificate.trustConfigured").value(true))
                .andExpect(jsonPath("$.pcCertificate.trustAnchorCount").value(1))
                .andExpect(jsonPath("$.pcCertificate.crlCount").value(2))
                .andExpect(jsonPath("$.pcCertificate.ocspSupported").value(false))
                .andExpect(jsonPath("$.mobile.configured").value(false))
                .andExpect(jsonPath("$.devMockCertificateEnabled").value(false));
        mvc.perform(post("/v1/certificate-enrollment/mobile/transactions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"CERTIFICATE_LOGIN\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MOBILE_PROVIDER_NOT_CONFIGURED"));
        mvc.perform(post("/v1/certificate-enrollment/mobile/transactions/" + UUID.randomUUID() + "/callback")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"SUCCESS\"}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/v1/certificate-enrollment/dev/mock-sign").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEV_MOCK_CERTIFICATE_DISABLED"));
    }

    @Test
    void certificateEnrollmentGatesActivationAndPhysicianReadsThenSignsTheDocument() throws Exception {
        String[] doctor = preregister();
        String doctorId = doctor[0];
        String challenge = mvc.perform(post("/v1/certificate-enrollment/challenges")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"invitationCode\":\"" + doctor[1] + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.purpose").value("CERTIFICATE_ENROLLMENT"))
                .andExpect(jsonPath("$.doctorId").value(doctorId))
                .andReturn().getResponse().getContentAsString();
        TestPki.Leaf leaf = PKI.leaf(TestPki.rsa(2048));
        String session = verify(challenge, leaf);
        String token = JsonPath.read(session, "$.activationToken");
        String registrationId = JsonPath.read(session, "$.enrollment.registrationId");

        // Admin headers cannot consent or activate for the doctor.
        mvc.perform(admin(post("/v1/certificate-enrollment/enrollments/" + registrationId + "/consent"))
                        .contentType(MediaType.APPLICATION_JSON).content(CONSENT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("DOCTOR_SESSION_REQUIRED"));
        mvc.perform(bearer(post("/v1/certificate-enrollment/enrollments/" + registrationId + "/consent"), token)
                        .contentType(MediaType.APPLICATION_JSON).content(CONSENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationStatus").value("PENDING"));
        mvc.perform(bearer(post("/v1/doctor-activation/activate"), token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_ENROLLMENT_REQUIRED"));

        mvc.perform(admin(post("/v1/admin/doctors/" + doctorId + "/certificate-reviews"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationId\":\"" + registrationId + "\",\"decision\":\"APPROVE\","
                                + "\"fingerprintSha256\":\"" + leaf.fingerprint()
                                + "\",\"evidenceRef\":\"evidence://license-scan\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationStatus").value("ACTIVE"));
        mvc.perform(admin(get("/v1/admin/doctors/" + doctorId + "/certificate-enrollments")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].fingerprintSha256").value(leaf.fingerprint()));
        mvc.perform(bearer(post("/v1/doctor-activation/activate"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Document → sign request → physician reads the exact bytes → approves.
        byte[] pdf = ("%PDF-1.7\nreport " + UUID.randomUUID() + "\n%%EOF").getBytes(StandardCharsets.US_ASCII);
        String metadata = "{\"externalDocumentId\":\"cert-" + UUID.randomUUID()
                + "\",\"externalStudyId\":\"s\",\"title\":\"t\",\"signerId\":\"" + doctorId + "\"}";
        String doc = mvc.perform(admin(multipart("/v1/documents"))
                        .file(new MockMultipartFile("metadata", "", MediaType.APPLICATION_JSON_VALUE,
                                metadata.getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("file", "r.pdf", MediaType.APPLICATION_PDF_VALUE, pdf))
                        .header("Idempotency-Key", "doc-" + UUID.randomUUID()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String request = mvc.perform(admin(post("/v1/sign-requests")).contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "req-" + UUID.randomUUID())
                        .content("{\"documentVersionId\":\"" + JsonPath.read(doc, "$.latestVersion.id")
                                + "\",\"signerId\":\"" + doctorId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String requestId = JsonPath.read(request, "$.id");
        String sha = JsonPath.read(request, "$.documentSha256");

        mvc.perform(get("/v1/doctor/sign-requests/" + requestId + "/document"))
                .andExpect(status().isUnauthorized());
        mvc.perform(admin(get("/v1/doctor/sign-requests/" + requestId + "/document")))
                .andExpect(status().isUnauthorized());
        mvc.perform(bearer(get("/v1/doctor/sign-requests/" + requestId + "/document"), token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("X-Document-Sha256", Hashing.sha256Hex(pdf)))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(pdf));

        String nonce = JsonPath.read(mvc.perform(bearer(get("/v1/doctor/sign-requests/" + requestId), token))
                .andReturn().getResponse().getContentAsString(), "$.nonce");
        mvc.perform(bearer(post("/v1/doctor/sign-requests/" + requestId + "/approve"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentSha256\":\"" + sha + "\",\"nonce\":\"" + nonce + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // Fresh certificate login.
        String login = mvc.perform(post("/v1/certificate-enrollment/login-challenges"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.purpose").value("CERTIFICATE_LOGIN"))
                .andReturn().getResponse().getContentAsString();
        byte[] loginPayload = Base64.getDecoder().decode((String) JsonPath.read(login, "$.signedPayloadBase64"));
        String loginSession = mvc.perform(post("/v1/certificate-enrollment/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + JsonPath.read(login, "$.challengeId")
                                + "\",\"fingerprintSha256\":\"" + leaf.fingerprint() + "\",\"certificate\":\""
                                + leaf.der() + "\",\"chain\":[\"" + PKI.intermediateDer() + "\"],\"signature\":\""
                                + TestPki.sign(leaf.keys(), "SHA256withRSA", loginPayload)
                                + "\",\"signatureAlgorithm\":\"SHA256withRSA\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doctor.id").value(doctorId))
                .andReturn().getResponse().getContentAsString();
        String loginToken = JsonPath.read(loginSession, "$.activationToken");

        // Unregister drops every session and blocks document access and signing.
        mvc.perform(bearer(post("/v1/certificate-enrollment/enrollments/" + registrationId + "/unregister"),
                        loginToken).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"device lost\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationStatus").value("REVOKED"));
        mvc.perform(bearer(get("/v1/doctor-activation/me"), token)).andExpect(status().isUnauthorized());
        mvc.perform(bearer(get("/v1/doctor/sign-requests/" + requestId + "/document"), loginToken))
                .andExpect(status().isUnauthorized());
    }
}
