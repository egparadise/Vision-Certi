package com.inviz.visioncerti.document;

import com.inviz.visioncerti.admin.SettingKey;
import com.inviz.visioncerti.admin.SystemSettings;
import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.common.Hashing;
import com.inviz.visioncerti.document.DocumentDtos.DocumentMetadata;
import com.inviz.visioncerti.document.DocumentDtos.DocumentResponse;
import com.inviz.visioncerti.security.ActorDirectory;
import com.inviz.visioncerti.security.RequestActor;
import com.inviz.visioncerti.signing.RegisteredSigners;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DocumentService {

    /** Result of a registration: {@code created} is false when the identical registration already existed. */
    public record Registration(DocumentResponse document, boolean created) {
    }

    /** Result of an integration artifact registration; {@code version} is the version holding exactly these bytes. */
    public record ArtifactRegistration(UUID documentId, DocumentVersion version, boolean newVersion) {
    }

    /** Content type of PACS report snapshots registered through the integration API (UTF-8 JSON, not a PDF). */
    public static final String PACS_REPORT_CONTENT_TYPE = "application/vnd.saintview.pacs-report+json;version=1";

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private final DocumentRepository repository;
    private final ActorDirectory directory;
    private final RegisteredSigners registeredSigners;
    private final AuditService audit;
    private final Clock clock;
    private final long maxBytes;

    public DocumentService(DocumentRepository repository, ActorDirectory directory, AuditService audit, Clock clock,
            long maxBytes) {
        this(repository, directory, RegisteredSigners.NONE, audit, clock, maxBytes);
    }

    @Autowired
    public DocumentService(DocumentRepository repository, ActorDirectory directory,
            RegisteredSigners registeredSigners, AuditService audit, Clock clock,
            @Value("${visioncerti.documents.max-bytes:20971520}") long maxBytes) {
        this.repository = repository;
        repository.attach(audit.store());
        this.directory = directory;
        this.registeredSigners = registeredSigners;
        this.audit = audit;
        this.clock = clock;
        this.maxBytes = maxBytes;
    }

    private volatile SystemSettings settings;

    /** Administrator overrides ({@link SystemSettings}); absent in unit tests that construct the service directly. */
    @Autowired(required = false)
    public void useSettings(SystemSettings settings) {
        this.settings = settings;
    }

    private long maxBytes() {
        SystemSettings s = settings;
        return s == null ? maxBytes : s.value(SettingKey.DOCUMENT_MAX_BYTES);
    }

    public Registration register(RequestActor actor, DocumentMetadata metadata, byte[] content) {
        validateMetadata(metadata);
        validatePdf(content);
        if (!directory.isDoctorInTenant(actor.tenantId(), metadata.signerId())
                && !registeredSigners.isActiveSigner(actor.tenantId(), metadata.signerId())) {
            throw ApiException.unprocessable("SIGNER_NOT_ELIGIBLE",
                    "signerId is not an active doctor of this tenant");
        }
        String sha256 = Hashing.sha256Hex(content);
        return repository.store().tx(tx -> {
            tx.lock("document|" + actor.tenantId() + "|" + metadata.externalDocumentId());
            return registerLocked(actor, metadata, content, sha256);
        });
    }

    private Registration registerLocked(RequestActor actor, DocumentMetadata metadata, byte[] content, String sha256) {
        Instant now = clock.instant();
        Document existing = repository.findByExternalId(actor.tenantId(), metadata.externalDocumentId()).orElse(null);
        if (existing != null) {
            // Re-sending the same registration is harmless; different bytes must use the versions endpoint.
            if (!existing.latestVersion().sha256().equals(sha256)) {
                throw ApiException.conflict("DOCUMENT_VERSION_CONFLICT",
                        "externalDocumentId already exists with different bytes; use POST /v1/documents/{id}/versions");
            }
            if (!existing.externalStudyId().equals(metadata.externalStudyId())
                    || !existing.title().equals(metadata.title())
                    || !existing.signerId().equals(metadata.signerId())) {
                throw ApiException.conflict("DOCUMENT_METADATA_CONFLICT",
                        "externalDocumentId already exists with different metadata");
            }
            return new Registration(DocumentResponse.from(existing), false);
        }
        Document document = new Document(UUID.randomUUID(), actor.tenantId(), metadata.externalDocumentId(),
                metadata.externalStudyId(), metadata.title(), metadata.signerId(), now, actor.actorId());
        DocumentVersion version = document.appendVersion(sha256, "application/pdf", now, actor.actorId(), content,
                null);
        repository.save(document, version, true);
        auditVersion(actor, document, version);
        return new Registration(DocumentResponse.from(document), true);
    }

    /**
     * Appends a new immutable version. {@code previousVersionId} must be the current latest version
     * (optimistic concurrency); earlier versions and requests bound to them are left untouched.
     */
    public DocumentResponse addVersion(RequestActor actor, UUID documentId, UUID previousVersionId,
            String amendmentReason, byte[] content) {
        if (previousVersionId == null) {
            throw ApiException.unprocessable("VALIDATION_FAILED", "previousVersionId is required");
        }
        requireText("amendmentReason", amendmentReason, 1000);
        validatePdf(content);
        return repository.store().tx(tx -> addVersionLocked(actor, documentId, previousVersionId, amendmentReason,
                content));
    }

    private DocumentResponse addVersionLocked(RequestActor actor, UUID documentId, UUID previousVersionId,
            String amendmentReason, byte[] content) {
        Document document = repository.lockFind(actor.tenantId(), documentId)
                .orElseThrow(() -> ApiException.notFound("DOCUMENT_NOT_FOUND", "Document not found"));
        if (!document.latestVersion().id().equals(previousVersionId)) {
            throw ApiException.conflict("DOCUMENT_VERSION_SUPERSEDED", "previousVersionId is not the latest version");
        }
        String sha256 = Hashing.sha256Hex(content);
        if (document.latestVersion().sha256().equals(sha256)) {
            throw ApiException.conflict("DOCUMENT_VERSION_UNCHANGED", "file is identical to the latest version");
        }
        DocumentVersion version = document.appendVersion(sha256, "application/pdf", clock.instant(), actor.actorId(),
                content, amendmentReason);
        repository.save(document, version, false);
        auditVersion(actor, document, version);
        return DocumentResponse.from(document);
    }

    /**
     * Registers integration-supplied bytes (already validated by the caller) as an immutable version of the
     * document identified by {@code externalDocumentId}. Identical bytes reuse the latest version; different bytes
     * append a new version, which supersedes requests bound to earlier versions. Never replaces a version.
     */
    public ArtifactRegistration registerArtifact(String tenantId, String actorId,
            String externalDocumentId, String externalStudyId, String title, String signerId, String contentType,
            byte[] content, String amendmentReason) {
        validateMetadata(new DocumentMetadata(externalDocumentId, externalStudyId, title, signerId));
        if (content == null || content.length == 0 || content.length > maxBytes()) {
            throw ApiException.unprocessable("VALIDATION_FAILED", "content is empty or too large");
        }
        if (!registeredSigners.isActiveSigner(tenantId, signerId)) {
            throw ApiException.unprocessable("SIGNER_NOT_ELIGIBLE", "signerId is not an active doctor of this tenant");
        }
        String sha256 = Hashing.sha256Hex(content);
        return repository.store().tx(tx -> {
            tx.lock("document|" + tenantId + "|" + externalDocumentId);
            return registerArtifactLocked(tenantId, actorId, externalDocumentId, externalStudyId, title, signerId,
                    contentType, content, amendmentReason, sha256);
        });
    }

    private ArtifactRegistration registerArtifactLocked(String tenantId, String actorId, String externalDocumentId,
            String externalStudyId, String title, String signerId, String contentType, byte[] content,
            String amendmentReason, String sha256) {
        Instant now = clock.instant();
        RequestActor actor = new RequestActor(tenantId, actorId, com.inviz.visioncerti.security.ActorRole.ADMIN);
        Document existing = repository.findByExternalId(tenantId, externalDocumentId).orElse(null);
        if (existing == null) {
            Document document = new Document(UUID.randomUUID(), tenantId, externalDocumentId, externalStudyId, title,
                    signerId, now, actorId);
            DocumentVersion version = document.appendVersion(sha256, contentType, now, actorId, content, null);
            repository.save(document, version, true);
            auditVersion(actor, document, version);
            return new ArtifactRegistration(document.id(), version, true);
        }
        if (!existing.externalStudyId().equals(externalStudyId) || !existing.signerId().equals(signerId)) {
            throw ApiException.conflict("DOCUMENT_METADATA_CONFLICT",
                    "externalReportId already exists with a different study or signer");
        }
        DocumentVersion latest = existing.latestVersion();
        if (latest.sha256().equals(sha256) && latest.contentType().equals(contentType)) {
            return new ArtifactRegistration(existing.id(), latest, false);
        }
        DocumentVersion version = existing.appendVersion(sha256, contentType, now, actorId, content,
                amendmentReason);
        repository.save(existing, version, false);
        auditVersion(actor, existing, version);
        return new ArtifactRegistration(existing.id(), version, true);
    }

    private void auditVersion(RequestActor actor, Document document, DocumentVersion version) {
        audit.record(actor.tenantId(), actor.actorId(), "DOCUMENT_VERSION_REGISTERED", "DOCUMENT_VERSION",
                version.id().toString(), AuditService.SUCCESS, Map.of(
                        "documentId", document.id().toString(),
                        "versionNumber", Integer.toString(version.versionNumber()),
                        "sha256", version.sha256()));
    }

    public List<DocumentResponse> list(RequestActor actor) {
        return repository.list(actor.tenantId()).stream().map(DocumentResponse::from).toList();
    }

    public DocumentResponse get(RequestActor actor, UUID id) {
        return repository.find(actor.tenantId(), id).map(DocumentResponse::from)
                .orElseThrow(() -> ApiException.notFound("DOCUMENT_NOT_FOUND", "Document not found"));
    }

    public DocumentVersion getVersion(RequestActor actor, UUID documentId, UUID versionId) {
        return repository.findVersion(actor.tenantId(), versionId)
                .filter(v -> v.documentId().equals(documentId))
                .orElseThrow(() -> ApiException.notFound("DOCUMENT_VERSION_NOT_FOUND", "Document version not found"));
    }

    private static void validateMetadata(DocumentMetadata m) {
        if (m == null) {
            throw ApiException.unprocessable("VALIDATION_FAILED", "metadata is required");
        }
        requireText("externalDocumentId", m.externalDocumentId(), 128);
        requireText("externalStudyId", m.externalStudyId(), 128);
        requireText("title", m.title(), 256);
        requireText("signerId", m.signerId(), 128);
    }

    private static void requireText(String field, String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw ApiException.unprocessable("VALIDATION_FAILED", field + " must be 1-" + max + " characters");
        }
    }

    private void validatePdf(byte[] content) {
        if (content == null || content.length == 0) {
            throw ApiException.unprocessable("INVALID_PDF", "file is empty");
        }
        if (content.length > maxBytes()) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "DOCUMENT_TOO_LARGE",
                    "file exceeds " + maxBytes() + " bytes");
        }
        if (content.length < PDF_MAGIC.length
                || !Arrays.equals(Arrays.copyOf(content, PDF_MAGIC.length), PDF_MAGIC)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "INVALID_PDF",
                    "file is not a PDF (missing %PDF- header)");
        }
    }
}
