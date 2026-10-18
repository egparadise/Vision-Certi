package com.inviz.visioncerti.integration;

import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.common.Hashing;
import com.inviz.visioncerti.common.IdempotencyService;
import com.inviz.visioncerti.document.DocumentService;
import com.inviz.visioncerti.document.DocumentService.ArtifactRegistration;
import com.inviz.visioncerti.integration.PacsIntegrationDtos.ReportSignRequestCreate;
import com.inviz.visioncerti.integration.PacsIntegrationDtos.ReportSignRequestCreated;
import com.inviz.visioncerti.integration.PacsIntegrationDtos.ReportSignRequestStatus;
import com.inviz.visioncerti.integration.PacsIntegrationDtos.SignatureView;
import com.inviz.visioncerti.security.ActorRole;
import com.inviz.visioncerti.security.RequestActor;
import com.inviz.visioncerti.signing.Signature;
import com.inviz.visioncerti.signing.SignRequestService;
import com.inviz.visioncerti.signing.SignRequestService.IntegrationView;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registers PACS report snapshots (exact UTF-8 JSON bytes, not PDFs) as immutable document versions and binds a
 * registered-physician sign request to them. The physician approves at Vision-Certi; the service only learns the
 * outcome and the signature evidence. The nonce is never disclosed to the service.
 */
public class PacsReportSignService {

    public static final String SNAPSHOT_SCHEMA = "saintview-pacs-report-snapshot/v1";
    static final int MAX_SNAPSHOT_BYTES = 256 * 1024;
    private static final Pattern EXTERNAL_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DocumentService documents;
    private final SignRequestService signRequests;
    private final IdempotencyService idempotency;
    private final PacsHandoffService handoffs;

    public PacsReportSignService(DocumentService documents, SignRequestService signRequests,
            IdempotencyService idempotency, PacsHandoffService handoffs) {
        this.documents = documents;
        this.signRequests = signRequests;
        this.idempotency = idempotency;
        this.handoffs = handoffs;
    }

    public ResponseEntity<ReportSignRequestCreated> create(IntegrationClient client, String idempotencyKey,
            ReportSignRequestCreate body) {
        if (body == null) {
            throw invalid("body is required");
        }
        if (!client.allowsTenant(body.tenantId())) {
            throw ApiException.forbidden("INTEGRATION_TENANT_FORBIDDEN", "tenantId is outside this client's scope");
        }
        UUID doctorId = parseUuid(body.doctorId());
        if (doctorId == null || body.revision() == null || body.revision() < 1 || body.revision() > 100_000
                || body.externalReportId() == null || !EXTERNAL_ID.matcher(body.externalReportId()).matches()
                || body.externalStudyId() == null || !EXTERNAL_ID.matcher(body.externalStudyId()).matches()
                || body.title() == null || body.title().isBlank() || body.title().length() > 256
                || !DocumentService.PACS_REPORT_CONTENT_TYPE.equals(body.contentType())
                || body.contentSha256() == null || !HEX64.matcher(body.contentSha256()).matches()) {
            throw invalid("tenantId, doctorId, externalReportId, externalStudyId, revision, title, contentType and "
                    + "contentSha256 are required in their documented formats");
        }
        byte[] content;
        try {
            content = Base64.getDecoder().decode(body.contentBase64() == null ? "" : body.contentBase64());
        } catch (IllegalArgumentException e) {
            throw invalid("contentBase64 is not valid base64");
        }
        if (content.length == 0 || content.length > MAX_SNAPSHOT_BYTES) {
            throw invalid("content must be 1-" + MAX_SNAPSHOT_BYTES + " bytes");
        }
        String sha256 = Hashing.sha256Hex(content);
        if (!Hashing.constantTimeEquals(sha256, body.contentSha256())) {
            throw ApiException.conflict("DOCUMENT_HASH_MISMATCH", "contentSha256 does not match the content bytes");
        }
        validateSnapshot(content, body, doctorId);
        handoffs.confirmReportCertificate(client, body.tenantId(), doctorId,
                body.pacsSessionId(), body.certificateFingerprintSha256());
        RequestActor actor = new RequestActor(body.tenantId(), client.actorId(), ActorRole.ADMIN);
        String fingerprint = Hashing.sha256Hex(String.join("\u0000", body.tenantId(), doctorId.toString(),
                body.externalReportId(), body.externalStudyId(), body.revision().toString(), body.title(),
                body.contentType(), sha256, body.pacsSessionId(), body.certificateFingerprintSha256()));
        return idempotency.execute(actor, "PACS_REPORT_SIGN_REQUEST", idempotencyKey, fingerprint, () -> {
            ArtifactRegistration registration = documents.registerArtifact(body.tenantId(), client.actorId(),
                    body.externalReportId(), body.externalStudyId(), body.title(), doctorId.toString(),
                    body.contentType(), content, "PACS report revision " + body.revision());
            IntegrationView view = signRequests.createForIntegration(body.tenantId(), client.actorId(),
                    registration.documentId(), registration.version().id(), doctorId.toString());
            return ResponseEntity.status(HttpStatus.CREATED).body(new ReportSignRequestCreated(view.request().id(),
                    registration.documentId(), registration.version().id(), view.request().documentSha256(),
                    view.request().signerId(), view.request().status().name(), view.request().expiresAt(),
                    body.revision()));
        });
    }

    public ReportSignRequestStatus get(IntegrationClient client, UUID id) {
        for (String tenantId : client.tenantIds()) {
            IntegrationView view;
            try {
                view = signRequests.getForIntegration(tenantId, client.actorId(), id);
            } catch (ApiException e) {
                if (e.status() == HttpStatus.NOT_FOUND) {
                    continue;
                }
                throw e;
            }
            Signature s = view.signature();
            SignatureView signature = s == null ? null : new SignatureView(s.id(), s.algorithm(), s.signatureMode(),
                    s.signatureValue(), s.createdAt(), s.documentSha256(), s.doctorEvidence());
            return new ReportSignRequestStatus(view.request().id(), tenantId, view.request().status().name(),
                    view.request().documentVersionId(), view.request().documentSha256(), view.request().signerId(),
                    view.request().expiresAt(), view.request().approvedAt(), signature);
        }
        throw ApiException.notFound("SIGN_REQUEST_NOT_FOUND", "Sign request not found");
    }

    /** The bytes must be strict UTF-8 JSON of the snapshot schema, describing exactly this request. */
    private static void validateSnapshot(byte[] content, ReportSignRequestCreate body, UUID doctorId) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            throw snapshotInvalid("content is not valid UTF-8");
        }
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (RuntimeException e) {
            throw snapshotInvalid("content is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw snapshotInvalid("content must be a JSON object");
        }
        JsonNode report = root.path("report");
        if (!SNAPSHOT_SCHEMA.equals(root.path("schema").asString(""))
                || !body.tenantId().equals(root.path("tenantId").asString(""))
                || !doctorId.toString().equals(root.path("doctorId").asString(""))
                || !report.isObject()
                || !body.externalReportId().equals(report.path("externalReportId").asString(""))
                || !report.path("revision").isIntegralNumber()
                || report.path("revision").asLong() != body.revision()) {
            throw snapshotInvalid("snapshot schema, tenantId, doctorId, report.externalReportId and report.revision "
                    + "must match the request");
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ApiException invalid(String message) {
        return ApiException.unprocessable("VALIDATION_FAILED", message);
    }

    private static ApiException snapshotInvalid(String message) {
        return ApiException.unprocessable("REPORT_SNAPSHOT_INVALID", message);
    }
}
