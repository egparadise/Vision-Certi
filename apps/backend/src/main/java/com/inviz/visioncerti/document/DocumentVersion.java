package com.inviz.visioncerti.document;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable stored version of a document. The exact uploaded bytes are kept privately and only
 * handed out as copies, so neither callers nor later uploads can change what a version hash refers to.
 * Persistence stores the metadata as JSON and the bytes separately (immutable {@code blobs} row), so
 * {@code content} is transient and present only on versions loaded through {@link DocumentRepository#findVersion}.
 */
public final class DocumentVersion {

    private final UUID id;
    private final UUID documentId;
    private final String tenantId;
    private final int versionNumber;
    private final String sha256;
    private final String contentType;
    private final Instant createdAt;
    private final String createdBy;
    private final UUID previousVersionId;
    private final String amendmentReason;
    private final long sizeBytes;
    private final transient byte[] content;

    DocumentVersion(UUID id, UUID documentId, String tenantId, int versionNumber, String sha256,
            String contentType, Instant createdAt, String createdBy, UUID previousVersionId, String amendmentReason,
            byte[] content) {
        this.id = id;
        this.documentId = documentId;
        this.tenantId = tenantId;
        this.versionNumber = versionNumber;
        this.sha256 = sha256;
        this.contentType = contentType;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        this.previousVersionId = previousVersionId;
        this.amendmentReason = amendmentReason;
        this.sizeBytes = content.length;
        this.content = content.clone();
    }

    /** For the JSON codec only. */
    private DocumentVersion() {
        this(null, null, null, 0, null, null, null, null, null, null, new byte[0]);
    }

    /** Same metadata with the stored bytes attached. */
    DocumentVersion withContent(byte[] bytes) {
        return new DocumentVersion(id, documentId, tenantId, versionNumber, sha256, contentType, createdAt, createdBy,
                previousVersionId, amendmentReason, bytes);
    }

    public UUID id() {
        return id;
    }

    public UUID documentId() {
        return documentId;
    }

    public String tenantId() {
        return tenantId;
    }

    public int versionNumber() {
        return versionNumber;
    }

    public String sha256() {
        return sha256;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public String contentType() {
        return contentType;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String createdBy() {
        return createdBy;
    }

    /** Null for version 1. */
    public UUID previousVersionId() {
        return previousVersionId;
    }

    /** Null for version 1. */
    public String amendmentReason() {
        return amendmentReason;
    }

    /** Defensive copy of the stored bytes. */
    public byte[] content() {
        if (content == null) {
            throw new IllegalStateException("document version loaded without content");
        }
        return content.clone();
    }

    /** Bytes for the repository to persist (no copy). */
    byte[] rawContent() {
        return content;
    }
}
