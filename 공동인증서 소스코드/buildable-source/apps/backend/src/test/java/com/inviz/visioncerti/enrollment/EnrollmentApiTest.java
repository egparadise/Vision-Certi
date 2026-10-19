package com.inviz.visioncerti.enrollment;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Public signup over HTTP in the dev profile, without any X-Tenant-Id / X-Actor-Id headers. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class EnrollmentApiTest {

    private static final AtomicInteger PHONE_SEQ = new AtomicInteger(ThreadLocalRandom.current().nextInt(1_000_000));
    private static final String PASSWORD = "S3cret-Passphrase!";

    @Autowired
    private MockMvc mvc;

    private static String newPhone() {
        return String.format("010-%04d-%04d", 2000 + PHONE_SEQ.get() % 7000, PHONE_SEQ.incrementAndGet() % 10000);
    }

    private static MockHttpServletRequestBuilder json(String path, String body) {
        String source = "198.51.100." + ThreadLocalRandom.current().nextInt(1, 255) + "-" + UUID.randomUUID();
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body).with(r -> {
            r.setRemoteAddr(source);
            return r;
        });
    }

    private String[] challenge(String phone) throws Exception {
        String body = mvc.perform(json("/v1/enrollment/phone-challenges", "{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.challengeId").isString())
                .andExpect(jsonPath("$.expiresAt").isString())
                .andExpect(jsonPath("$.resendAfter").isString())
                .andExpect(jsonPath("$.mode").value("MOCK_SMS"))
                .andExpect(jsonPath("$.devCode").isString())
                .andReturn().getResponse().getContentAsString();
        return new String[] {JsonPath.read(body, "$.challengeId"), JsonPath.read(body, "$.devCode")};
    }

    private String verify(String challengeId, String code) throws Exception {
        String body = mvc.perform(json("/v1/enrollment/phone-challenges/" + challengeId + "/verify",
                        "{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationToken").isString())
                .andExpect(jsonPath("$.expiresAt").isString())
                .andExpect(jsonPath("$.mode").value("MOCK_SMS"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.verificationToken");
    }

    private String token(String phone) throws Exception {
        String[] c = challenge(phone);
        return verify(c[0], c[1]);
    }

    private static String registration(String type, String username, String password, String phone, String token,
            String clinicianFields, String consents) {
        return "{\"accountType\":\"" + type + "\",\"username\":\"" + username + "\",\"password\":\"" + password
                + "\",\"name\":\"김테스트\",\"phone\":\"" + phone + "\",\"email\":null," + clinicianFields
                + "\"verificationToken\":" + (token == null ? "null" : "\"" + token + "\"") + ",\"consents\":"
                + consents + "}";
    }

    private static final String NO_CLINICIAN = "\"institution\":null,\"licenseNumber\":null,\"specialty\":null,";
    private static final String CONSENTS =
            "{\"authentication\":true,\"signature\":true,\"privacy\":true,\"version\":\"2026-10-13-draft\"}";

    private static String uniqueUsername(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void personalSignupWorksWithoutDevHeaders() throws Exception {
        String phone = newPhone();
        String token = token(phone);
        String username = uniqueUsername("user");
        mvc.perform(json("/v1/enrollment/registrations",
                        registration("PERSONAL", username, PASSWORD, phone, token, NO_CLINICIAN, CONSENTS)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.registrationId").isString())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.accountType").value("PERSONAL"))
                .andExpect(jsonPath("$.status").value("REGISTERED"))
                .andExpect(jsonPath("$.phoneVerification").value("MOCK_VERIFIED"))
                .andExpect(jsonPath("$.registeredAt").isString())
                .andExpect(content().string(not(containsString(PASSWORD))))
                .andExpect(content().string(not(containsString(token))));

        // Replay of the consumed proof.
        mvc.perform(json("/v1/enrollment/registrations",
                        registration("PERSONAL", uniqueUsername("user"), PASSWORD, phone, token, NO_CLINICIAN,
                                CONSENTS)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PHONE_VERIFICATION_INVALID"))
                .andExpect(jsonPath("$.correlationId").isString())
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void registrationWithoutPhoneVerificationIsRefused() throws Exception {
        mvc.perform(json("/v1/enrollment/registrations",
                        registration("PERSONAL", uniqueUsername("user"), PASSWORD, newPhone(), null, NO_CLINICIAN,
                                CONSENTS)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PHONE_VERIFICATION_REQUIRED"));
    }

    @Test
    void tokenIsBoundToPhone() throws Exception {
        String token = token(newPhone());
        mvc.perform(json("/v1/enrollment/registrations",
                        registration("PERSONAL", uniqueUsername("user"), PASSWORD, newPhone(), token, NO_CLINICIAN,
                                CONSENTS)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PHONE_VERIFICATION_MISMATCH"));
    }

    @Test
    void clinicianSelfSignupIsRejectedAndPersonalAccountsGetNoPrivilege() throws Exception {
        String phone = newPhone();
        String token = token(phone);
        String username = uniqueUsername("dr");
        String clinician = "\"institution\":\"서울영상의학센터\",\"licenseNumber\":\"123456\",\"specialty\":\"영상의학과\",";
        mvc.perform(json("/v1/enrollment/registrations",
                        registration("CLINICIAN", username, PASSWORD, phone, token, clinician, CONSENTS)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CLINICIAN_SELF_REGISTRATION_DISABLED"));

        mvc.perform(json("/v1/enrollment/registrations",
                        registration("PERSONAL", username, PASSWORD, phone, token, NO_CLINICIAN, CONSENTS)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REGISTERED"));

        // The new account is not a dev identity and cannot reach protected routes.
        mvc.perform(get("/v1/documents").header("X-Tenant-Id", "demo-hospital-a").header("X-Actor-Id", username))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("DEV_IDENTITY_UNKNOWN"));
    }

    @Test
    void invalidConsentsAndPasswordAreRejectedWithoutEchoAndWithoutConsumingProof() throws Exception {
        String phone = newPhone();
        String token = token(phone);
        String username = uniqueUsername("user");
        String noPrivacy =
                "{\"authentication\":true,\"signature\":true,\"privacy\":false,\"version\":\"2026-10-13-draft\"}";
        mvc.perform(json("/v1/enrollment/registrations",
                        registration("PERSONAL", username, PASSWORD, phone, token, NO_CLINICIAN, noPrivacy)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
        String oldVersion = "{\"authentication\":true,\"signature\":true,\"privacy\":true,\"version\":\"2026-01-01\"}";
        mvc.perform(json("/v1/enrollment/registrations",
                        registration("PERSONAL", username, PASSWORD, phone, token, NO_CLINICIAN, oldVersion)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CONSENT_VERSION_MISMATCH"));
        mvc.perform(json("/v1/enrollment/registrations",
                        registration("PERSONAL", username, "tooShort#1", phone, token, NO_CLINICIAN, CONSENTS)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(content().string(not(containsString("tooShort#1"))));
        mvc.perform(json("/v1/enrollment/registrations",
                        registration("PERSONAL", "admin-demo", PASSWORD, phone, token, NO_CLINICIAN, CONSENTS)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_UNAVAILABLE"));
        mvc.perform(json("/v1/enrollment/registrations",
                        registration("PERSONAL", username, PASSWORD, phone, token, NO_CLINICIAN, CONSENTS)))
                .andExpect(status().isCreated());
    }

    @Test
    void wrongCodesLockAndCooldownIsEnforced() throws Exception {
        String phone = newPhone();
        String[] c = challenge(phone);
        String wrong = c[1].equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 4; i++) {
            mvc.perform(json("/v1/enrollment/phone-challenges/" + c[0] + "/verify", "{\"code\":\"" + wrong + "\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("PHONE_CODE_MISMATCH"));
        }
        mvc.perform(json("/v1/enrollment/phone-challenges/" + c[0] + "/verify", "{\"code\":\"" + wrong + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PHONE_CHALLENGE_LOCKED"));
        mvc.perform(json("/v1/enrollment/phone-challenges/" + c[0] + "/verify", "{\"code\":\"" + c[1] + "\"}"))
                .andExpect(status().isTooManyRequests());

        mvc.perform(json("/v1/enrollment/phone-challenges", "{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PHONE_CHALLENGE_COOLDOWN"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void reVerificationCannotMintAnotherToken() throws Exception {
        String[] c = challenge(newPhone());
        verify(c[0], c[1]);
        mvc.perform(json("/v1/enrollment/phone-challenges/" + c[0] + "/verify", "{\"code\":\"" + c[1] + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHONE_CHALLENGE_ALREADY_VERIFIED"));
    }

    @Test
    void invalidPhoneIsRejectedWithoutEcho() throws Exception {
        mvc.perform(json("/v1/enrollment/phone-challenges", "{\"phone\":\"011-9876-5432\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(content().string(not(containsString("9876"))));
        mvc.perform(json("/v1/enrollment/phone-challenges", "{\"phone\":12345"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void onlyExactSignupRoutesArePublic() throws Exception {
        mvc.perform(get("/v1/enrollment/registrations")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/enrollment/phone-challenges")).andExpect(status().isUnauthorized());
        mvc.perform(json("/v1/enrollment/phone-challenges/", "{\"phone\":\"01012345678\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(json("/v1/enrollment/phone-challenges/not-a-uuid/verify", "{\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(json("/v1/enrollment/phone-challenges/" + UUID.randomUUID() + "/verify/extra",
                        "{\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(json("/v1/documents", "{}")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("DEV_IDENTITY_REQUIRED"));
        mvc.perform(json("/v1/enrollment/phone-challenges/" + UUID.randomUUID() + "/verify", "{\"code\":\"123456\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PHONE_CHALLENGE_NOT_FOUND"));
    }
}
