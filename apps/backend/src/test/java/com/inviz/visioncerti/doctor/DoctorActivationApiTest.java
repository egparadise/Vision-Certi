package com.inviz.visioncerti.doctor;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inviz.visioncerti.doctor.identity.GoogleIdTokenVerifier;
import com.inviz.visioncerti.doctor.identity.TestGoogleTokens;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * HTTP flow with DEV_SIMULATED keys, the dev MOCK_IDENTITY route enabled and a Google verifier whose JWKS is a local
 * test key (real signature verification, test issuer key). Admin uses dev headers; the physician uses only a bearer.
 */
@SpringBootTest(properties = {
        "visioncerti.doctor.admin-invitations-enabled=true",
        "visioncerti.doctor.signing-backend=DEV_SIMULATED",
        "visioncerti.doctor.activation-policy=GOOGLE_PERMITTED_DEV",
        "visioncerti.doctor.dev-identity-enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DoctorActivationApiTest {

    static final TestGoogleTokens GOOGLE = new TestGoogleTokens(Clock.systemUTC());
    private static final AtomicInteger SEQ = new AtomicInteger(5000);
    private static final String A = "demo-hospital-a";
    private static final String B = "demo-hospital-b";
    private static final String CONSENT =
            "{\"version\":\"2026-10-13-draft\",\"authentication\":true,\"signature\":true,\"privacy\":true}";

    @TestConfiguration
    static class GoogleTestConfig {
        @Bean
        @Primary
        GoogleIdTokenVerifier testGoogleIdTokenVerifier() {
            return GOOGLE.verifier();
        }
    }

    @Autowired
    private MockMvc mvc;

    private static <T extends AbstractMockHttpServletRequestBuilder<?>> T admin(T builder, String tenant) {
        builder.header("X-Tenant-Id", tenant).header("X-Actor-Id", "admin-demo");
        return builder;
    }

    private static <T extends AbstractMockHttpServletRequestBuilder<?>> T bearer(T builder, String token) {
        builder.header("Authorization", "Bearer " + token);
        return builder;
    }

    private static String json(Object... kv) {
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append(kv[i]).append("\":\"").append(kv[i + 1]).append('"');
        }
        return out.append('}').toString();
    }

    /** Returns [doctorId, invitationCode]. */
    private String[] preregister(String tenant, String email) throws Exception {
        String body = mvc.perform(admin(post("/v1/admin/doctors"), tenant).contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "김의사", "licenseNumber", Integer.toString(SEQ.incrementAndGet()),
                                "centerName", "서울영상센터", "googleEmail", email, "identityEvidenceRef", "evidence://id",
                                "licenseEvidenceRef", "evidence://license")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.doctor.status").value("PENDING"))
                .andExpect(jsonPath("$.doctor.certificate").value(nullValue()))
                .andExpect(jsonPath("$.invitationCode").isString())
                .andReturn().getResponse().getContentAsString();
        return new String[] {JsonPath.read(body, "$.doctor.id"), JsonPath.read(body, "$.invitationCode")};
    }

    /** Returns [challengeId, nonce]. */
    private String[] challenge(String invitationCode) throws Exception {
        String body = mvc.perform(post("/v1/doctor-activation/challenges").contentType(MediaType.APPLICATION_JSON)
                        .content(json("invitationCode", invitationCode)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.purpose").value("ACTIVATION"))
                .andReturn().getResponse().getContentAsString();
        return new String[] {JsonPath.read(body, "$.challengeId"), JsonPath.read(body, "$.nonce")};
    }

    private String googleSession(String[] challenge, String subject, String email) throws Exception {
        String body = mvc.perform(post("/v1/doctor-activation/google").contentType(MediaType.APPLICATION_JSON)
                        .content(json("challengeId", challenge[0], "idToken",
                                GOOGLE.token(subject, email, challenge[1]))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identityMode").value("GOOGLE"))
                .andExpect(jsonPath("$.doctor.status").value("IDENTITY_VERIFIED"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.activationToken");
    }

    /** Returns [doctorId, sessionToken] of an ACTIVE doctor in tenant A. */
    private String[] activeDoctor() throws Exception {
        String email = "dr" + SEQ.incrementAndGet() + "@example.com";
        String[] doctor = preregister(A, email);
        String token = googleSession(challenge(doctor[1]), "sub-" + UUID.randomUUID(), email);
        mvc.perform(bearer(post("/v1/doctor-activation/consent"), token).contentType(MediaType.APPLICATION_JSON)
                .content(CONSENT)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONSENTED"));
        mvc.perform(bearer(post("/v1/doctor-activation/activate"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.certificate.kind").value("SIMULATED_NOT_X509"))
                .andExpect(jsonPath("$.certificate.status").value("VALID"));
        return new String[] {doctor[0], token};
    }

    private String uploadFor(String doctorId, String externalId) throws Exception {
        String metadata = "{\"externalDocumentId\":\"" + externalId + "\",\"externalStudyId\":\"s-" + externalId
                + "\",\"title\":\"Synthetic\",\"signerId\":\"" + doctorId + "\"}";
        String body = mvc.perform(admin(multipart("/v1/documents"), A)
                        .file(new MockMultipartFile("metadata", "", MediaType.APPLICATION_JSON_VALUE,
                                metadata.getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("file", "r.pdf", MediaType.APPLICATION_PDF_VALUE,
                                ("%PDF-1.7\n" + externalId + "\n%%EOF").getBytes(StandardCharsets.US_ASCII)))
                        .header("Idempotency-Key", "doc-" + UUID.randomUUID()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.latestVersion.id");
    }

    /** Returns [requestId, sha256]. */
    private String[] signRequest(String doctorId, String versionId) throws Exception {
        String body = mvc.perform(admin(post("/v1/sign-requests"), A).contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "req-" + UUID.randomUUID())
                        .content(json("documentVersionId", versionId, "signerId", doctorId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nonce").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        return new String[] {JsonPath.read(body, "$.id"), JsonPath.read(body, "$.documentSha256")};
    }

    @Test
    void configIsPublicAndHonest() throws Exception {
        mvc.perform(get("/v1/doctor-activation/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.googleConfigured").value(true))
                .andExpect(jsonPath("$.googleClientId").value(TestGoogleTokens.CLIENT_ID))
                .andExpect(jsonPath("$.devSimulationEnabled").value(true))
                .andExpect(jsonPath("$.signingBackend").value("DEV_SIMULATED"))
                .andExpect(jsonPath("$.signingBackendAvailable").value(true))
                .andExpect(jsonPath("$.clinicalUseAllowed").value(false));
    }

    @Test
    void physicianActivatesAndSignsThroughBearerSessionOnly() throws Exception {
        String[] doctor = activeDoctor();
        String doctorId = doctor[0];
        String token = doctor[1];

        mvc.perform(bearer(get("/v1/doctor-activation/me"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doctor.id").value(doctorId))
                .andExpect(jsonPath("$.identityMode").value("GOOGLE"))
                .andExpect(jsonPath("$.certificate.backend").value("DEV_SIMULATED"));

        String[] req = signRequest(doctorId, uploadFor(doctorId, "rpt-" + UUID.randomUUID()));

        // Dev headers can never act as the registered physician.
        mvc.perform(post("/v1/sign-requests/" + req[0] + "/approve").header("X-Tenant-Id", A)
                        .header("X-Actor-Id", doctorId).header("Idempotency-Key", "apr-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json("documentSha256", req[1], "nonce", "x")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("DEV_IDENTITY_UNKNOWN"));
        mvc.perform(post("/v1/sign-requests/" + req[0] + "/approve").header("X-Tenant-Id", A)
                        .header("X-Actor-Id", "doctor-demo").header("Idempotency-Key", "apr-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json("documentSha256", req[1], "nonce", "x")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIGNER_MISMATCH"));

        String list = mvc.perform(bearer(get("/v1/doctor/sign-requests"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(req[0]))
                .andExpect(jsonPath("$.items[0].nonce").isString())
                .andReturn().getResponse().getContentAsString();
        String nonce = JsonPath.read(list, "$.items[0].nonce");

        // No session → no approval; a bogus session too.
        mvc.perform(post("/v1/doctor/sign-requests/" + req[0] + "/approve").contentType(MediaType.APPLICATION_JSON)
                        .content(json("documentSha256", req[1], "nonce", nonce)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("DOCTOR_SESSION_REQUIRED"));

        String done = mvc.perform(bearer(post("/v1/doctor/sign-requests/" + req[0] + "/approve"), token)
                        .contentType(MediaType.APPLICATION_JSON).content(json("documentSha256", req[1], "nonce", nonce)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.signatureMode").value("DEV_SIMULATED_ECDSA_NOT_VALID_FOR_CLINICAL_USE"))
                .andReturn().getResponse().getContentAsString();
        String signatureId = JsonPath.read(done, "$.signatureId");

        mvc.perform(bearer(post("/v1/doctor/sign-requests/" + req[0] + "/approve"), token)
                        .contentType(MediaType.APPLICATION_JSON).content(json("documentSha256", req[1], "nonce", nonce)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NONCE_ALREADY_USED"));

        mvc.perform(admin(get("/v1/signatures"), A))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"doctorEvidence\":{\"doctorId\":\"" + doctorId)))
                .andExpect(content().string(containsString("RAW_ECDSA_P256_SHA256_DER")));
        mvc.perform(admin(post("/v1/signatures/" + signatureId + "/verify"), A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INDETERMINATE"))
                .andExpect(jsonPath("$.checks.documentIntegrity").value("VALID"))
                .andExpect(jsonPath("$.checks.signature").value("INDETERMINATE"))
                .andExpect(jsonPath("$.clinicalUseAllowed").value(false))
                .andExpect(jsonPath("$.reasonCodes", org.hamcrest.Matchers.hasItem("SIMULATED_KEY_SIGNATURE_INTACT")));
    }

    @Test
    void adminCannotConsentOrActivateAndRolesAndTenantsAreEnforced() throws Exception {
        String email = "dr" + SEQ.incrementAndGet() + "@example.com";
        String[] doctor = preregister(A, email);

        mvc.perform(admin(post("/v1/doctor-activation/consent"), A).contentType(MediaType.APPLICATION_JSON)
                        .content(CONSENT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("DOCTOR_SESSION_REQUIRED"));
        mvc.perform(admin(post("/v1/doctor-activation/activate"), A))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/admin/doctors").header("X-Tenant-Id", A).header("X-Actor-Id", "doctor-demo")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_ROLE_REQUIRED"));
        mvc.perform(get("/v1/admin/doctors")).andExpect(status().isUnauthorized());
        mvc.perform(admin(get("/v1/admin/doctors/" + doctor[0]), B))
                .andExpect(status().isNotFound());
        mvc.perform(admin(get("/v1/admin/doctors"), A))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(doctor[1]))));
        mvc.perform(admin(get("/v1/admin/doctors/" + doctor[0] + "/evidence"), A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("ADMIN_VERIFICATION"));
        // Exact routes: other methods fall back to the dev identity filter.
        mvc.perform(get("/v1/doctor-activation/consent")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/doctor-activation/config")).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongGoogleAccountOrReusedCodeIsRejectedWithoutDoctorData() throws Exception {
        String email = "dr" + SEQ.incrementAndGet() + "@example.com";
        String[] doctor = preregister(A, email);
        String[] c = challenge(doctor[1]);
        mvc.perform(post("/v1/doctor-activation/google").contentType(MediaType.APPLICATION_JSON)
                        .content(json("challengeId", c[0], "idToken", GOOGLE.token("sub-z", "other@example.com", c[1]))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GOOGLE_EMAIL_MISMATCH"));
        mvc.perform(post("/v1/doctor-activation/google").contentType(MediaType.APPLICATION_JSON)
                        .content(json("challengeId", c[0], "idToken", GOOGLE.token("sub-z", email, c[1]))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHALLENGE_ALREADY_USED"));
        String[] c2 = challenge(doctor[1]);
        mvc.perform(post("/v1/doctor-activation/google").contentType(MediaType.APPLICATION_JSON)
                        .content(json("challengeId", c2[0], "idToken", "eyJhbGciOiJub25lIn0.e30.")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ID_TOKEN_INVALID"));
        googleSession(challenge(doctor[1]), "sub-ok-" + UUID.randomUUID(), email);
        mvc.perform(post("/v1/doctor-activation/challenges").contentType(MediaType.APPLICATION_JSON)
                        .content(json("invitationCode", doctor[1])))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVITATION_INVALID"))
                .andExpect(content().string(not(containsString(doctor[0]))))
                .andExpect(content().string(not(containsString(email))));
    }

    @Test
    void suspensionStopsSessionsAndEligibility() throws Exception {
        String[] doctor = activeDoctor();
        mvc.perform(admin(patch("/v1/admin/doctors/" + doctor[0] + "/status"), A)
                        .contentType(MediaType.APPLICATION_JSON).content(json("status", "SUSPENDED", "reason", "자격 확인")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        mvc.perform(bearer(get("/v1/doctor-activation/me"), doctor[1]))
                .andExpect(status().is4xxClientError());
        mvc.perform(bearer(get("/v1/doctor/sign-requests"), doctor[1]))
                .andExpect(status().is4xxClientError());
        String metadata = "{\"externalDocumentId\":\"x-" + UUID.randomUUID()
                + "\",\"externalStudyId\":\"s\",\"title\":\"t\",\"signerId\":\"" + doctor[0] + "\"}";
        mvc.perform(admin(multipart("/v1/documents"), A)
                        .file(new MockMultipartFile("metadata", "", MediaType.APPLICATION_JSON_VALUE,
                                metadata.getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("file", "r.pdf", MediaType.APPLICATION_PDF_VALUE,
                                "%PDF-1.7\nx\n%%EOF".getBytes(StandardCharsets.US_ASCII)))
                        .header("Idempotency-Key", "doc-" + UUID.randomUUID()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SIGNER_NOT_ELIGIBLE"));
    }

    @Test
    void devIdentityRouteIsLabeledMock() throws Exception {
        String email = "dr" + SEQ.incrementAndGet() + "@example.com";
        String[] doctor = preregister(A, email);
        String[] c = challenge(doctor[1]);
        mvc.perform(post("/v1/doctor-activation/dev-identity").contentType(MediaType.APPLICATION_JSON)
                        .content(json("challengeId", c[0], "email", email, "subject", "mock-" + UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identityMode").value("MOCK_IDENTITY"))
                .andExpect(jsonPath("$.doctor.googleAccountLinked").value(false));
    }

    @Test
    void returningDoctorLogsInWithGoogle() throws Exception {
        String email = "dr" + SEQ.incrementAndGet() + "@example.com";
        String subject = "sub-return-" + UUID.randomUUID();
        String[] doctor = preregister(A, email);
        googleSession(challenge(doctor[1]), subject, email);
        String body = mvc.perform(post("/v1/doctor-activation/login-challenges"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.purpose").value("LOGIN"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/v1/doctor-activation/google").contentType(MediaType.APPLICATION_JSON)
                        .content(json("challengeId", JsonPath.read(body, "$.challengeId"), "idToken",
                                GOOGLE.token(subject, email, JsonPath.read(body, "$.nonce")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doctor.id").value(doctor[0]))
                .andExpect(jsonPath("$.activationToken").isString());
    }
}
