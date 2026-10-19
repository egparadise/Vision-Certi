package com.inviz.visioncerti.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * Admin mode: runtime operational settings (role, editor tenant, range, reason, optimistic revision, audit, effect on
 * the services) and the read-only deployment configuration (no secrets). The context is discarded afterwards because
 * the settings are system-wide.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminModeApiTest {

    private static final String A = "demo-hospital-a";  // configured editor tenant in the dev profile
    private static final String B = "demo-hospital-b";
    private static final String ADMIN = "admin-demo";
    private static final String DOCTOR = "doctor-demo";

    @Autowired
    private MockMvc mvc;

    private static <T extends AbstractMockHttpServletRequestBuilder<?>> T as(T builder, String tenant, String actor) {
        builder.header("X-Tenant-Id", tenant).header("X-Actor-Id", actor);
        return builder;
    }

    private ResultActions change(String tenant, String key, long value, String reason, long revision)
            throws Exception {
        return mvc.perform(as(put("/v1/admin/settings/" + key), tenant, ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":" + value + ",\"reason\":\"" + reason + "\",\"expectedRevision\":" + revision
                        + "}"));
    }

    private ResultActions reset(String key, long revision) throws Exception {
        return mvc.perform(as(post("/v1/admin/settings/" + key + "/reset"), A, ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"restore default\",\"expectedRevision\":" + revision + "}"));
    }

    @Test
    void settingsAreAdminOnlyAndEditableOnlyByTheOperatingTenant() throws Exception {
        mvc.perform(as(get("/v1/admin/settings"), A, DOCTOR)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_ROLE_REQUIRED"));
        mvc.perform(as(get("/v1/admin/settings"), A, ADMIN)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.items[*].key", hasItem("sign-requests.ttl")))
                .andExpect(jsonPath("$.items[?(@.key=='sign-requests.ttl')].defaultValue").value(600))
                .andExpect(jsonPath("$.items[?(@.key=='sign-requests.ttl')].min").value(60));
        // Another tenant's administrator sees the same system-wide values read-only and cannot change them.
        mvc.perform(as(get("/v1/admin/settings"), B, ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(false))
                .andExpect(jsonPath("$.editableReason").isNotEmpty());
        change(B, "sign-requests.ttl", 120, "tenant b tries", 0).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SETTINGS_READ_ONLY"));
        mvc.perform(as(put("/v1/admin/settings/sign-requests.ttl"), A, DOCTOR).contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":120,\"reason\":\"doctor tries\",\"expectedRevision\":0}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidChangesAreRefused() throws Exception {
        change(A, "sign-requests.ttl", 59, "too short", 0).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SETTING_OUT_OF_RANGE"));
        change(A, "sign-requests.ttl", 3_601, "too long", 0).andExpect(status().isUnprocessableContent());
        change(A, "sign-requests.ttl", 120, "x", 0).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        change(A, "no.such.setting", 1, "unknown key", 0).andExpect(status().isNotFound());
        mvc.perform(as(put("/v1/admin/settings/sign-requests.ttl"), A, ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":120,\"reason\":\"missing revision\"}"))
                .andExpect(status().isUnprocessableContent());
        reset("pacs.handoff-ttl", 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SETTING_ALREADY_DEFAULT"));
    }

    @Test
    void signRequestTtlChangeIsAuditedAppliesToNewRequestsAndCanBeReset() throws Exception {
        change(A, "sign-requests.ttl", 120, "shorter approval window", 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(120)).andExpect(jsonPath("$.overridden").value(true))
                .andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.updatedBy").value(ADMIN));
        // Stale revision: another administrator changed it first.
        change(A, "sign-requests.ttl", 300, "stale", 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SETTING_CHANGED_CONCURRENTLY"));

        String versionId = JsonPath.read(upload("ttl-" + UUID.randomUUID(), pdf(1_000)), "$.latestVersion.id");
        String request = mvc.perform(as(post("/v1/sign-requests"), A, ADMIN)
                        .header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentVersionId\":\"" + versionId + "\",\"signerId\":\"" + DOCTOR + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Instant created = Instant.parse(JsonPath.read(request, "$.createdAt"));
        Instant expires = Instant.parse(JsonPath.read(request, "$.expiresAt"));
        assertThat(Duration.between(created, expires)).isEqualTo(Duration.ofSeconds(120));

        mvc.perform(as(get("/v1/audit-events"), A, ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].action", hasItem("SYSTEM_SETTING_CHANGED")));
        reset("sign-requests.ttl", 1).andExpect(status().isOk()).andExpect(jsonPath("$.value").value(600))
                .andExpect(jsonPath("$.overridden").value(false)).andExpect(jsonPath("$.revision").value(0));
        mvc.perform(as(get("/v1/audit-events"), A, ADMIN))
                .andExpect(jsonPath("$.items[*].action", hasItem("SYSTEM_SETTING_RESET")));
    }

    @Test
    void documentSizeLimitTakesEffectImmediately() throws Exception {
        byte[] large = pdf(1_500_000);
        change(A, "documents.max-bytes", 1_048_576, "small uploads only", 0).andExpect(status().isOk());
        mvc.perform(uploadRequest("big-" + UUID.randomUUID(), large)).andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("DOCUMENT_TOO_LARGE"));
        reset("documents.max-bytes", 1).andExpect(status().isOk());
        mvc.perform(uploadRequest("big-" + UUID.randomUUID(), large)).andExpect(status().isCreated());
    }

    @Test
    void systemConfigurationIsReadOnlyAndNeverReturnsSecrets() throws Exception {
        mvc.perform(as(get("/v1/admin/system/configuration"), A, DOCTOR)).andExpect(status().isForbidden());
        String body = mvc.perform(as(get("/v1/admin/system/configuration"), A, ADMIN)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.activeProfiles", hasItem("dev")))
                .andExpect(jsonPath("$.persistenceMode").value("IN_MEMORY"))
                .andExpect(jsonPath("$.settingsEditorTenants", hasItem(A)))
                .andExpect(jsonPath("$.settingsEditable").value(true))
                .andExpect(jsonPath("$.items[?(@.key=='visioncerti.staff-auth.mode')].value").value("DEV_HEADERS"))
                .andReturn().getResponse().getContentAsString();
        // Secrets and file paths: presence only.
        assertThat((java.util.List<Object>) JsonPath.read(body, "$.items[?(@.secret==true && @.value != null)]"))
                .isEmpty();
        mvc.perform(as(get("/v1/admin/system/configuration"), B, ADMIN))
                .andExpect(jsonPath("$.settingsEditable").value(false));
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] pdf(int size) {
        byte[] head = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        byte[] tail = "\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[Math.max(size, head.length + tail.length)];
        Arrays.fill(out, (byte) ' ');
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(tail, 0, out, out.length - tail.length, tail.length);
        return out;
    }

    private AbstractMockHttpServletRequestBuilder<?> uploadRequest(String externalId, byte[] file) {
        String metadata = "{\"externalDocumentId\":\"" + externalId + "\",\"externalStudyId\":\"study-" + externalId
                + "\",\"title\":\"Synthetic report\",\"signerId\":\"" + DOCTOR + "\"}";
        return as(multipart("/v1/documents")
                .file(new MockMultipartFile("metadata", "", MediaType.APPLICATION_JSON_VALUE,
                        metadata.getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("file", "report.pdf", MediaType.APPLICATION_PDF_VALUE, file))
                .header("Idempotency-Key", "k-" + UUID.randomUUID()), A, ADMIN);
    }

    private String upload(String externalId, byte[] file) throws Exception {
        return mvc.perform(uploadRequest(externalId, file)).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
    }
}
