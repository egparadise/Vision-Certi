package com.inviz.visioncerti.document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped document identified externally by {@code externalDocumentId}. Versions are append-only;
 * an existing version is never replaced or removed.
 */
public final class Document {

    private final UUID id;
    private final String tenantId;
    private final String externalDocumentId;
    private final String externalStudyId;
    private final Instant createdAt;
    private final String createdBy;
    private final String title;
    private final String signerId;
    private Instant updatedAt;
    private final List<DocumentVersion> versions = new ArrayList<>();

    Document(UUID id, String tenantId, String externalDocumentId, String externalStudyId, String title,
            String signerId, Instant createdAt, String createdBy) {
        this.id = id;
        this.tenantId = tenantId;
        this.externalDocumentId = externalDocumentId;
        this.externalStudyId = externalStudyId;
        this.title = title;
        this.signerId = signerId;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.createdBy = createdBy;
    }

    /** For the JSON codec only. */
    private Document() {
        this(null, null, null, null, null, null, null, null);
    }

    DocumentVersion appendVersion(String sha256, String contentType, Instant now, String actorId, byte[] content,
            String amendmentReason) {
        UUID previous = versions.isEmpty() ? null : latestVersion().id();
        DocumentVersion version = new DocumentVersion(UUID.randomUUID(), id, tenantId, versions.size() + 1, sha256,
                contentType, now, actorId, previous, amendmentReason, content);
        versions.add(version);
        this.updatedAt = now;
        return version;
    }

    public UUID id() {
        return id;
    }

    public String tenantId() {
        return tenantId;
    }

    public String externalDocumentId() {
        return externalDocumentId;
    }

    public String externalStudyId() {
        return externalStudyId;
    }

    public String title() {
        return title;
    }

    public String signerId() {
        return signerId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public String createdBy() {
        return createdBy;
    }

    public List<DocumentVersion> versions() {
        return List.copyOf(versions);
    }

    public DocumentVersion latestVersion() {
        return versions.getLast();
    }
}
