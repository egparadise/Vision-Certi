package com.inviz.visioncerti.doctor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Without configuration every capability reports itself unavailable and fails closed. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DoctorDefaultsApiTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void defaultsAreExplicitlyUnavailable() throws Exception {
        mvc.perform(get("/v1/doctor-activation/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.googleClientId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.googleConfigured").value(false))
                .andExpect(jsonPath("$.devSimulationEnabled").value(false))
                .andExpect(jsonPath("$.signingBackend").value("NOT_CONFIGURED"))
                .andExpect(jsonPath("$.signingBackendAvailable").value(false))
                .andExpect(jsonPath("$.clinicalUseAllowed").value(false))
                .andExpect(jsonPath("$.activationPolicy").value("CERTIFICATE_REQUIRED"))
                .andExpect(jsonPath("$.adminInvitationsEnabled").value(false));

        mvc.perform(post("/v1/doctor-activation/google").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + UUID.randomUUID() + "\",\"idToken\":\"x\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GOOGLE_NOT_CONFIGURED"));

        String body = mvc.perform(post("/v1/doctor-activation/login-challenges"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/v1/doctor-activation/dev-identity").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + JsonPath.read(body, "$.challengeId")
                                + "\",\"email\":\"a@example.com\",\"subject\":\"s\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEV_IDENTITY_DISABLED"));
    }

    @Test
    void defaultDisablesAdminCreationReissueAndPublicInvitationEntryPoints() throws Exception {
        mvc.perform(post("/v1/admin/doctors").header("X-Tenant-Id", "demo-hospital-a")
                        .header("X-Actor-Id", "admin-demo").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_INVITATIONS_DISABLED"));
        mvc.perform(post("/v1/admin/doctors/" + UUID.randomUUID() + "/invitation")
                        .header("X-Tenant-Id", "demo-hospital-a").header("X-Actor-Id", "admin-demo"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_INVITATIONS_DISABLED"));
        for (String path : new String[]{"/v1/doctor-activation/challenges", "/v1/certificate-enrollment/challenges"}) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"invitationCode\":\"OLD-INVITE\"}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_INVITATIONS_DISABLED"));
        }
    }
}
