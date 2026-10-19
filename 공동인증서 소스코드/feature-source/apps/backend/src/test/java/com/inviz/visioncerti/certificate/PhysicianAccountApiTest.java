package com.inviz.visioncerti.certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.doctor.DoctorService;
import com.inviz.visioncerti.doctor.PhysicianAccountService;
import com.inviz.visioncerti.enrollment.PasswordHasher;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"visioncerti.doctor.signing-backend=DEV_SIMULATED",
        "visioncerti.doctor.admin-invitations-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class PhysicianAccountApiTest {
    static final TestPki PKI = CertificateEnrollmentApiTest.PKI;
    static final AtomicInteger SEQ = new AtomicInteger(700000);
    static final String BASE = "/v1/physician-accounts";
    static final String PASSWORD = "Only-For-Tests-2026!";
    static final String TENANT = "demo-hospital-a";
    static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired MockMvc mvc;
    @Autowired AuditService audit;
    @Autowired DoctorService doctors;
    @Autowired PasswordHasher hasher;
    @Autowired Clock clock;
    @DynamicPropertySource static void trust(DynamicPropertyRegistry r) throws Exception {
        CertificateEnrollmentApiTest.trustFiles(r);
    }
    static String field(String body, String path) { return JsonPath.read(body, path); }
    static String body(Object value) { return JSON.writeValueAsString(value); }
    static String result(ResultActions action) throws Exception { return action.andReturn().getResponse().getContentAsString(); }
    static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }
    static <T extends org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder<?>> T admin(T request) {
        request.header("X-Tenant-Id", TENANT).header("X-Actor-Id", "admin-demo");
        return request;
    }
    static Map<String,Object> registration(int n) {
        return Map.of("username", "physician" + n, "password", PASSWORD, "name", "시험 의사", "phone", "01012345678",
                "licenseNumber", "" + n, "centerName", "시험 의료기관", "privacyConsent", true,
                "consentVersion", PhysicianAccountService.CONSENT_VERSION);
    }
    String register(int n) throws Exception {
        return result(mvc.perform(post(BASE + "/registrations").contentType(MediaType.APPLICATION_JSON).content(body(registration(n))))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.account.scope").value("ENROLLMENT_ONLY"))
                .andExpect(jsonPath("$.account.phoneVerified").value(false))
                .andExpect(jsonPath("$.account.doctor.status").value("PENDING")));
    }
    String challenge(String token) throws Exception {
        return result(mvc.perform(bearer(post(BASE + "/certificate-challenges"), token)).andExpect(status().isCreated()));
    }
    Map<String,Object> proof(String challenge, TestPki.Leaf leaf, boolean login) throws Exception {
        return Map.of(login ? "challengeId" : "registrationId", field(challenge, "$.challengeId"),
                "certificate", leaf.der(), "chain", java.util.List.of(PKI.intermediateDer()),
                "signature", TestPki.sign(leaf.keys(), "SHA256withRSA", Base64.getDecoder().decode(field(challenge,"$.signedPayloadBase64"))),
                "signatureAlgorithm", "SHA256withRSA", "fingerprintSha256", leaf.fingerprint());
    }
    String verify(String challenge, TestPki.Leaf leaf) throws Exception {
        return result(mvc.perform(post("/v1/certificate-enrollment/verify").contentType(MediaType.APPLICATION_JSON)
                .content(body(proof(challenge, leaf, false)))).andExpect(status().isCreated()));
    }

    @Test void registrationPasswordSessionsPersistButCannotEnterClinicalApis() throws Exception {
        int n = SEQ.incrementAndGet(); String receipt = register(n); String token = field(receipt, "$.enrollmentToken");
        mvc.perform(bearer(get("/v1/doctor-activation/me"), token)).andExpect(status().isUnauthorized());
        mvc.perform(bearer(get("/v1/doctor/sign-requests"), token)).andExpect(status().isUnauthorized());
        mvc.perform(bearer(post("/v1/doctor-activation/activate"), token)).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/registrations").contentType(MediaType.APPLICATION_JSON).content(body(registration(n))))
                .andExpect(status().isConflict());
        mvc.perform(post(BASE + "/sessions").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("username", "physician" + n, "password", "wrong")))).andExpect(status().isUnauthorized());
        var restarted = new PhysicianAccountService(audit, doctors, hasher, clock, TENANT);
        assertThat(restarted.me("Bearer " + token).accountId().toString()).isEqualTo(field(receipt, "$.account.accountId"));
        String again = result(mvc.perform(post(BASE + "/sessions").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("username", ("physician" + n).toUpperCase(), "password", PASSWORD))))
                .andExpect(status().isOk()));
        mvc.perform(bearer(get(BASE + "/me"), token)).andExpect(status().isUnauthorized());
        String next = field(again, "$.enrollmentToken");
        var later = new PhysicianAccountService(audit, doctors, hasher, Clock.offset(clock, Duration.ofMinutes(21)), TENANT);
        assertThatThrownBy(() -> later.me("Bearer " + next)).isInstanceOf(ApiException.class);
        mvc.perform(bearer(post(BASE + "/logout"), next)).andExpect(status().isNoContent());
        mvc.perform(bearer(post(BASE + "/certificate-challenges"), next)).andExpect(status().isUnauthorized());
    }

    @Test void signedProofMedicalReviewLoginAndDocumentApprovalWorkWithoutSdk() throws Exception {
        int n = SEQ.incrementAndGet(); String receipt = register(n);
        String accountToken = field(receipt,"$.enrollmentToken"), doctor = field(receipt,"$.account.doctor.id");
        TestPki.Leaf leaf = PKI.leaf(TestPki.rsa(2048));
        String challenge = challenge(accountToken), verified = verify(challenge, leaf);
        String registration = field(verified,"$.enrollment.registrationId"), token = field(verified,"$.activationToken");
        mvc.perform(bearer(get("/v1/doctor/sign-requests"),token)).andExpect(status().isForbidden());
        mvc.perform(bearer(get("/v1/doctor/sign-requests/" + UUID.randomUUID() + "/document"),token)).andExpect(status().isForbidden());
        assertThat((Boolean)JsonPath.read(verified,"$.enrollment.medicalCredentialsRequired")).isTrue();
        mvc.perform(post("/v1/certificate-enrollment/verify").contentType(MediaType.APPLICATION_JSON)
                .content(body(proof(challenge,leaf,false)))).andExpect(status().isConflict());
        // Fresh ownership proof resumes the same pending record without creating a duplicate or approving it.
        String resumed = verify(challenge(accountToken),leaf);
        assertThat(field(resumed,"$.enrollment.registrationId")).isEqualTo(registration);
        assertThat(field(resumed,"$.enrollment.reviewStatus")).isEqualTo("PENDING");
        token = field(resumed,"$.activationToken");
        mvc.perform(bearer(post("/v1/doctor-activation/activate"),token)).andExpect(status().isConflict());
        String medical = result(mvc.perform(bearer(put("/v1/certificate-enrollment/enrollments/"+registration+"/medical-credentials"),token)
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("medicalLicenseNumber",""+n,"specialist",true,
                        "specialistQualificationNumber","SP-TEST","specialty","영상의학과")))).andExpect(status().isOk()));
        mvc.perform(bearer(post("/v1/certificate-enrollment/enrollments/"+registration+"/consent"),token)
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("version","2026-10-13-draft",
                        "authentication",true,"signature",true,"privacy",true)))).andExpect(status().isOk());
        mvc.perform(admin(post("/v1/admin/doctors/"+doctor+"/certificate-reviews")).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("registrationId",registration,"decision","APPROVE","fingerprintSha256",leaf.fingerprint(),
                        "evidenceRef","test://verified-identity-license-specialist","medicalCredentialsSubmittedAt",field(medical,"$.medicalCredentialsSubmittedAt")))))
                .andExpect(status().isOk());
        mvc.perform(bearer(post("/v1/doctor-activation/activate"),token)).andExpect(status().isOk());
        String loginChallenge = result(mvc.perform(post("/v1/certificate-enrollment/login-challenges")).andExpect(status().isCreated()));
        String login = result(mvc.perform(post("/v1/certificate-enrollment/login").contentType(MediaType.APPLICATION_JSON)
                .content(body(proof(loginChallenge,leaf,true)))).andExpect(status().isOk()));
        String loginToken = field(login,"$.activationToken");
        assertThat(field(login,"$.doctor.id")).isEqualTo(doctor);
        byte[] pdf = "%PDF-1.7\nsynthetic report\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        String document = result(mvc.perform(admin(multipart("/v1/documents"))
                .file(new MockMultipartFile("metadata","",MediaType.APPLICATION_JSON_VALUE,
                        body(Map.of("externalDocumentId",UUID.randomUUID().toString(),"externalStudyId","test-study","title","test report","signerId",doctor)).getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("file","report.pdf",MediaType.APPLICATION_PDF_VALUE,pdf))
                .header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isCreated()));
        String sign = result(mvc.perform(admin(post("/v1/sign-requests")).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key",UUID.randomUUID().toString()).content(body(Map.of("documentVersionId",field(document,"$.latestVersion.id"),"signerId",doctor))))
                .andExpect(status().isCreated()));
        String id = field(sign,"$.id");
        String detail = result(mvc.perform(bearer(get("/v1/doctor/sign-requests/"+id),loginToken)).andExpect(status().isOk()));
        mvc.perform(bearer(post("/v1/doctor/sign-requests/"+id+"/approve"),accountToken).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("documentSha256",field(sign,"$.documentSha256"),"nonce",field(detail,"$.nonce")))))
                .andExpect(status().isUnauthorized());
        mvc.perform(bearer(post("/v1/doctor/sign-requests/"+id+"/approve"),loginToken).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("documentSha256",field(sign,"$.documentSha256"),"nonce",field(detail,"$.nonce")))))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(admin(patch("/v1/admin/doctors/"+doctor+"/status")).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("status","SUSPENDED","reason","test suspension")))).andExpect(status().isOk());
        mvc.perform(bearer(get(BASE+"/me"),accountToken)).andExpect(status().isForbidden());
    }

    @Test void anotherAccountCannotStealCertificateAndNoProofCannotAuthenticate() throws Exception {
        String first = register(SEQ.incrementAndGet()), second = register(SEQ.incrementAndGet());
        TestPki.Leaf leaf = PKI.leaf(TestPki.rsa(2048));
        verify(challenge(field(first,"$.enrollmentToken")),leaf);
        String other = challenge(field(second,"$.enrollmentToken"));
        mvc.perform(post("/v1/certificate-enrollment/verify").contentType(MediaType.APPLICATION_JSON)
                .content(body(proof(other,leaf,false)))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_ALREADY_BOUND"));
        String fresh = challenge(field(second,"$.enrollmentToken"));
        var invalid = new java.util.HashMap<>(proof(fresh,leaf,false)); invalid.put("signature",Base64.getEncoder().encodeToString(new byte[256]));
        mvc.perform(post("/v1/certificate-enrollment/verify").contentType(MediaType.APPLICATION_JSON).content(body(invalid)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("CERTIFICATE_SIGNATURE_INVALID"));
        mvc.perform(bearer(get("/v1/doctor/sign-requests"),field(second,"$.enrollmentToken"))).andExpect(status().isUnauthorized());
    }
}
