package com.inviz.visioncerti.certificate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** The opt-in dev MOCK_CERTIFICATE shortcut still runs path + CRL + review + consent and is labeled as mock. */
@SpringBootTest(properties = {
        "visioncerti.doctor.signing-backend=DEV_SIMULATED",
        "visioncerti.cert.dev-mock-enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DevMockCertificateApiTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void mockCertificateEnrollmentAndLoginAreLabeledAndStillReviewed() throws Exception {
        mvc.perform(get("/v1/certificate-enrollment/config"))
                .andExpect(jsonPath("$.devMockCertificateEnabled").value(true))
                .andExpect(jsonPath("$.pcCertificate.trustConfigured").value(true));

        String pre = mvc.perform(post("/v1/admin/doctors").header("X-Tenant-Id", "demo-hospital-a")
                        .header("X-Actor-Id", "admin-demo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"모의의사\",\"licenseNumber\":\"424242\",\"centerName\":\"모의센터\","
                                + "\"googleEmail\":\"mock-dr@example.com\",\"identityEvidenceRef\":\"e\","
                                + "\"licenseEvidenceRef\":\"e\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String doctorId = JsonPath.read(pre, "$.doctor.id");
        String challenge = mvc.perform(post("/v1/certificate-enrollment/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invitationCode\":\"" + JsonPath.read(pre, "$.invitationCode") + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String session = mvc.perform(post("/v1/certificate-enrollment/dev/mock-sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + JsonPath.read(challenge, "$.challengeId") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identityMode").value("MOCK_CERTIFICATE"))
                .andExpect(jsonPath("$.enrollment.kind").value("MOCK_CERTIFICATE"))
                .andExpect(jsonPath("$.enrollment.registrationStatus").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(session, "$.activationToken");
        String registrationId = JsonPath.read(session, "$.enrollment.registrationId");
        String fingerprint = JsonPath.read(session, "$.enrollment.fingerprintSha256");

        mvc.perform(post("/v1/certificate-enrollment/enrollments/" + registrationId + "/consent")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"2026-10-13-draft\",\"authentication\":true,\"signature\":true,"
                                + "\"privacy\":true}"))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/admin/doctors/" + doctorId + "/certificate-reviews").header("X-Tenant-Id",
                        "demo-hospital-a").header("X-Actor-Id", "admin-demo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationId\":\"" + registrationId + "\",\"decision\":\"APPROVE\","
                                + "\"fingerprintSha256\":\"" + fingerprint + "\",\"evidenceRef\":\"mock\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationStatus").value("ACTIVE"));
        mvc.perform(post("/v1/doctor-activation/activate").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        String login = mvc.perform(post("/v1/certificate-enrollment/login-challenges"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/v1/certificate-enrollment/dev/mock-sign").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + JsonPath.read(login, "$.challengeId")
                                + "\",\"fingerprintSha256\":\"" + fingerprint + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identityMode").value("MOCK_CERTIFICATE"))
                .andExpect(jsonPath("$.doctor.id").value(doctorId));
    }
}
