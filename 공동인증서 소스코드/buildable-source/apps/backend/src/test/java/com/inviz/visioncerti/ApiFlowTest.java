package com.inviz.visioncerti;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/** HTTP-level behaviour in the dev profile. Each test uses unique external IDs because the context is shared. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ApiFlowTest {

    private static final String A = "demo-hospital-a";
    private static final String B = "demo-hospital-b";
    private static final String DOCTOR = "doctor-demo";
    private static final String ADMIN = "admin-demo";

    @Autowired
    private MockMvc mvc;

    private static <T extends AbstractMockHttpServletRequestBuilder<?>> T as(T builder, String tenant, String actor) {
        builder.header("X-Tenant-Id", tenant).header("X-Actor-Id", actor);
        return builder;
    }

    private static String key() {
        return "test-" + UUID.randomUUID();
    }

    private static byte[] pdf(String body) {
        return ("%PDF-1.7\n" + body + "\n%%EOF").getBytes(StandardCharsets.US_ASCII);
    }

    private MockMultipartHttpServletRequestBuilder uploadRequest(String tenant, String externalId, byte[] file) {
        return uploadRequest(tenant, externalId, file, key());
    }

    private MockMultipartHttpServletRequestBuilder uploadRequest(String tenant, String externalId, byte[] file,
            String idempotencyKey) {
        String metadata = "{\"externalDocumentId\":\"" + externalId + "\",\"externalStudyId\":\"study-" + externalId
                + "\",\"title\":\"Synthetic report\",\"signerId\":\"" + DOCTOR + "\"}";
        return as(multipart("/v1/documents")
                .file(new MockMultipartFile("metadata", "", MediaType.APPLICATION_JSON_VALUE,
                        metadata.getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("file", "report.pdf", MediaType.APPLICATION_PDF_VALUE, file))
                .header("Idempotency-Key", idempotencyKey), tenant, ADMIN);
    }

    private String upload(String tenant, String externalId, byte[] file) throws Exception {
        MvcResult result = mvc.perform(uploadRequest(tenant, externalId, file))
                .andExpect(status().isCreated())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private String createRequest(String tenant, String versionId) throws Exception {
        return mvc.perform(as(post("/v1/sign-requests"), tenant, ADMIN)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentVersionId\":\"" + versionId + "\",\"signerId\":\"" + DOCTOR + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private static String approveBody(String sha, String nonce) {
        return "{\"documentSha256\":\"" + sha + "\",\"nonce\":\"" + nonce + "\"}";
    }

    @Test
    void healthIsPublicAndStatesMockMode() throws Exception {
        mvc.perform(get("/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.signatureMode").value("MOCK_NOT_VALID_FOR_CLINICAL_USE"))
                .andExpect(jsonPath("$.clinicalUseAllowed").value(false))
                .andExpect(jsonPath("$.persistence").value("IN_MEMORY"));
    }

    @Test
    void missingOrUnknownDevIdentityIsRejectedWithErrorShape() throws Exception {
        mvc.perform(get("/v1/documents").header("X-Correlation-Id", "corr-123"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Correlation-Id", "corr-123"))
                .andExpect(jsonPath("$.code").value("DEV_IDENTITY_REQUIRED"))
                .andExpect(jsonPath("$.correlationId").value("corr-123"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.message").isString());
        mvc.perform(as(get("/v1/documents"), "demo-hospital-z", DOCTOR))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("DEV_IDENTITY_UNKNOWN"));
        mvc.perform(as(get("/v1/documents"), A, "intruder"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tenantsCannotSeeOrActOnEachOthersData() throws Exception {
        String ext = "iso-" + UUID.randomUUID();
        String doc = upload(A, ext, pdf("tenant a only"));
        String docId = JsonPath.read(doc, "$.id");
        String versionId = JsonPath.read(doc, "$.latestVersion.id");
        String req = createRequest(A, versionId);
        String reqId = JsonPath.read(req, "$.id");
        String sha = JsonPath.read(req, "$.documentSha256");

        mvc.perform(as(get("/v1/documents"), B, DOCTOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", not(hasItem(docId))))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
        mvc.perform(as(get("/v1/documents/" + docId), B, DOCTOR))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
        mvc.perform(as(get("/v1/documents/" + docId + "/versions/" + versionId + "/content"), B, DOCTOR))
                .andExpect(status().isNotFound());
        mvc.perform(as(get("/v1/sign-requests/" + reqId), B, DOCTOR))
                .andExpect(status().isNotFound());
        mvc.perform(as(post("/v1/sign-requests"), B, ADMIN)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentVersionId\":\"" + versionId + "\",\"signerId\":\"" + DOCTOR + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_VERSION_NOT_FOUND"));
        mvc.perform(as(post("/v1/sign-requests/" + reqId + "/approve"), B, DOCTOR)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody(sha, "whatever")))
                .andExpect(status().isNotFound());

        // Tenant A still sees and owns everything.
        mvc.perform(as(get("/v1/documents/" + docId), A, DOCTOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(A));
        mvc.perform(as(get("/v1/audit-events"), B, DOCTOR))
                .andExpect(jsonPath("$.items[*].targetId", not(hasItem(reqId))));
    }

    @Test
    void fullFlowWithIdempotencyReplayNonceReplayAndAdminRefusal() throws Exception {
        String doc = upload(A, "flow-" + UUID.randomUUID(), pdf("flow"));
        String docId = JsonPath.read(doc, "$.id");
        String versionId = JsonPath.read(doc, "$.latestVersion.id");
        mvc.perform(as(get("/v1/documents/" + docId + "/versions/" + versionId + "/content"), A, DOCTOR))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes(pdf("flow")));

        String req = createRequest(A, versionId);
        String reqId = JsonPath.read(req, "$.id");
        String sha = JsonPath.read(req, "$.documentSha256");
        assertThat((Object) JsonPath.read(req, "$.nonce")).as("admin creator never sees nonce").isNull();
        assertThat((String) JsonPath.read(req, "$.status")).isEqualTo("PENDING_APPROVAL");
        assertThat((Object) JsonPath.read(req, "$.expiresAt")).isNotNull();

        String asDoctor = mvc.perform(as(get("/v1/sign-requests/" + reqId), A, DOCTOR))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String nonce = JsonPath.read(asDoctor, "$.nonce");
        assertThat(nonce).isNotBlank();

        // Admin holding the correct hash and nonce still cannot sign.
        mvc.perform(as(post("/v1/sign-requests/" + reqId + "/approve"), A, ADMIN)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody(sha, nonce)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIGNING_NOT_PERMITTED_FOR_ROLE"));

        // Mutations require Idempotency-Key.
        mvc.perform(as(post("/v1/sign-requests/" + reqId + "/approve"), A, DOCTOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody(sha, nonce)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        String approvalKey = key();
        String approved = mvc.perform(as(post("/v1/sign-requests/" + reqId + "/approve"), A, DOCTOR)
                        .header("Idempotency-Key", approvalKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody(sha, nonce)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.signatureMode").value("MOCK_NOT_VALID_FOR_CLINICAL_USE"))
                .andExpect(jsonPath("$.signatureId", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        String signatureId = JsonPath.read(approved, "$.signatureId");

        // Same key + same payload: identical stored response, no second signature.
        mvc.perform(as(post("/v1/sign-requests/" + reqId + "/approve"), A, DOCTOR)
                        .header("Idempotency-Key", approvalKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody(sha, nonce)))
                .andExpect(status().isAccepted())
                .andExpect(content().json(approved));
        // Same key + different payload: 409.
        mvc.perform(as(post("/v1/sign-requests/" + reqId + "/approve"), A, DOCTOR)
                        .header("Idempotency-Key", approvalKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody(sha, nonce + "x")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        // New key + used nonce: replay rejected.
        mvc.perform(as(post("/v1/sign-requests/" + reqId + "/approve"), A, DOCTOR)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody(sha, nonce)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NONCE_ALREADY_USED"));

        String signatures = mvc.perform(as(get("/v1/signatures"), A, DOCTOR))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((java.util.List<?>) JsonPath.read(signatures, "$.items[?(@.signRequestId == '" + reqId + "')]"))
                .hasSize(1);

        mvc.perform(as(post("/v1/signatures/" + signatureId + "/verify"), A, DOCTOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INDETERMINATE"))
                .andExpect(jsonPath("$.checks.documentIntegrity").value("VALID"))
                .andExpect(jsonPath("$.checks.signature").value("INDETERMINATE"))
                .andExpect(jsonPath("$.mockSignatureIntact").value(true))
                .andExpect(jsonPath("$.reasonCodes", hasItem("MOCK_NOT_VALID_FOR_CLINICAL_USE")));
        mvc.perform(as(post("/v1/signatures/" + signatureId + "/verify"), B, DOCTOR))
                .andExpect(status().isNotFound());

        String deliveries = mvc.perform(as(get("/v1/deliveries"), A, ADMIN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        java.util.List<String> deliveryIds = JsonPath.read(deliveries,
                "$.items[?(@.signatureId == '" + signatureId + "')].id");
        assertThat(deliveryIds).hasSize(1);
        mvc.perform(as(get("/v1/deliveries/" + deliveryIds.getFirst()), A, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"));

        mvc.perform(as(get("/v1/audit-events"), A, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].action", hasItem("SIGN_REQUEST_APPROVAL_DENIED")))
                .andExpect(jsonPath("$.items[*].action", hasItem("SIGNATURE_CREATED")));

        mvc.perform(as(get("/v1/dashboard"), A, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(A))
                .andExpect(jsonPath("$.signatureMode").value("MOCK_NOT_VALID_FOR_CLINICAL_USE"))
                .andExpect(jsonPath("$.signRequestsByStatus.COMPLETED").isNumber())
                .andExpect(jsonPath("$.deliveriesByStatus.QUEUED").isNumber())
                .andExpect(jsonPath("$.deliveriesByStatus.SENDING").value(0))
                .andExpect(jsonPath("$.deliveriesByStatus.ACKED").value(0))
                .andExpect(jsonPath("$.deliveriesByStatus.RETRY_WAIT").value(0))
                .andExpect(jsonPath("$.deliveriesByStatus.DEAD_LETTER").value(0))
                .andExpect(jsonPath("$.deliveriesByStatus.length()").value(5));
    }

    @Test
    void uploadValidationAndVersioning() throws Exception {
        String ext = "ver-" + UUID.randomUUID();
        mvc.perform(uploadRequest(A, ext, "not a pdf".getBytes(StandardCharsets.US_ASCII)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("INVALID_PDF"));

        String doc = upload(A, ext, pdf("v1"));
        String docId = JsonPath.read(doc, "$.id");
        String v1 = JsonPath.read(doc, "$.latestVersion.id");
        String v1Sha = JsonPath.read(doc, "$.latestVersion.sha256");

        mvc.perform(uploadRequest(A, ext, pdf("v1"))).andExpect(status().isOk());
        mvc.perform(uploadRequest(A, ext, pdf("changed")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_VERSION_CONFLICT"));

        mvc.perform(as(multipart("/v1/documents/" + docId + "/versions")
                        .file(new MockMultipartFile("file", "v2.pdf", MediaType.APPLICATION_PDF_VALUE, pdf("v2")))
                        .param("previousVersionId", v1)
                        .param("amendmentReason", "addendum")
                        .header("Idempotency-Key", key()), A, ADMIN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versions.length()").value(2))
                .andExpect(jsonPath("$.versions[0].id").value(v1))
                .andExpect(jsonPath("$.versions[0].sha256").value(v1Sha))
                .andExpect(jsonPath("$.latestVersion.versionNumber").value(2))
                .andExpect(jsonPath("$.latestVersion.previousVersionId").value(v1));

        mvc.perform(as(get("/v1/documents/" + docId + "/versions/" + v1 + "/content"), A, DOCTOR))
                .andExpect(content().bytes(pdf("v1")));
    }

    @Test
    void sameIdempotencyKeyWithDifferentUploadIsRejected() throws Exception {
        String sharedKey = key();
        String ext = "idem-" + UUID.randomUUID();
        mvc.perform(uploadRequest(A, ext, pdf("one"), sharedKey))
                .andExpect(status().isCreated());
        mvc.perform(uploadRequest(A, ext, pdf("two"), sharedKey))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void invalidApprovalBodyIs422() throws Exception {
        mvc.perform(as(post("/v1/sign-requests/" + UUID.randomUUID() + "/approve"), A, DOCTOR)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentSha256\":\"nothex\",\"nonce\":\"\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
