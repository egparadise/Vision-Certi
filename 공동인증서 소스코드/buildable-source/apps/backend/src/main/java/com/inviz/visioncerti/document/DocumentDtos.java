package com.inviz.visioncerti.document;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request/response shapes for {@code /v1/documents}. */
public final class DocumentDtos {

    private DocumentDtos() {
    }

    /** JSON in the {@code metadata} multipart part. */
    public record DocumentMetadata(String externalDocumentId, String externalStudyId, String title,
            String signerId) {
    }

    public record DocumentVersionResponse(UUID id, UUID documentId, int versionNumber, String sha256,
            long sizeBytes, String contentType, Instant createdAt, String createdBy, UUID previousVersionId,
            String amendmentReason) {

        static DocumentVersionResponse from(DocumentVersion v) {
            return new DocumentVersionResponse(v.id(), v.documentId(), v.versionNumber(), v.sha256(), v.sizeBytes(),
                    v.contentType(), v.createdAt(), v.createdBy(), v.previousVersionId(), v.amendmentReason());
        }
    }

    public record DocumentResponse(UUID id, String tenantId, String externalDocumentId, String externalStudyId,
            String title, String signerId, Instant createdAt, Instant updatedAt, String createdBy,
            DocumentVersionResponse latestVersion, List<DocumentVersionResponse> versions) {

        static DocumentResponse from(Document d) {
            return new DocumentResponse(d.id(), d.tenantId(), d.externalDocumentId(), d.externalStudyId(), d.title(),
                    d.signerId(), d.createdAt(), d.updatedAt(), d.createdBy(),
                    DocumentVersionResponse.from(d.latestVersion()),
                    d.versions().stream().map(DocumentVersionResponse::from).toList());
        }
    }
}
