package com.inviz.visioncerti.signing;

import java.time.Instant;
import java.util.UUID;

/**
 * Sign request bound to tenant, signer, exact document version and its SHA-256. The binding fields are
 * final; only lifecycle fields change, and only through {@link SignRequestService} inside a storage transaction
 * that holds the request's row lock.
 */
public final class SignRequest {

    private final UUID id;
    private final String tenantId;
    private final UUID documentId;
    private final UUID documentVersionId;
    private final String documentSha256;
    private final String signerId;
    private final String nonce;
    private final Instant createdAt;
    private final String createdBy;
    private final Instant expiresAt;
    private final boolean registeredDoctor;
    private SignRequestStatus status = SignRequestStatus.PENDING_APPROVAL;
    private boolean nonceConsumed;
    private Instant approvedAt;
    private String approvedBy;
    private UUID signatureId;

    SignRequest(UUID id, String tenantId, UUID documentId, UUID documentVersionId, String documentSha256,
            String signerId, String nonce, Instant createdAt, String createdBy, Instant expiresAt,
            boolean registeredDoctor) {
        this.id = id;
        this.tenantId = tenantId;
        this.documentId = documentId;
        this.documentVersionId = documentVersionId;
        this.documentSha256 = documentSha256;
        this.signerId = signerId;
        this.nonce = nonce;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        this.expiresAt = expiresAt;
        this.registeredDoctor = registeredDoctor;
    }

    /** For the JSON codec only. */
    private SignRequest() {
        this(null, null, null, null, null, null, null, null, null, null, false);
    }

    void markExpired() {
        status = SignRequestStatus.EXPIRED;
    }

    void markApproved(Instant at, String actorId) {
        nonceConsumed = true;
        approvedAt = at;
        approvedBy = actorId;
        status = SignRequestStatus.APPROVED;
    }

    void markSigning() {
        status = SignRequestStatus.SIGNING;
    }

    void markCompleted(UUID signatureId) {
        this.signatureId = signatureId;
        status = SignRequestStatus.COMPLETED;
    }

    void markFailed() {
        status = SignRequestStatus.FAILED;
    }

    public UUID id() {
        return id;
    }

    public String tenantId() {
        return tenantId;
    }

    public UUID documentId() {
        return documentId;
    }

    public UUID documentVersionId() {
        return documentVersionId;
    }

    public String documentSha256() {
        return documentSha256;
    }

    public String signerId() {
        return signerId;
    }

    public String nonce() {
        return nonce;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String createdBy() {
        return createdBy;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    /** True when the signer is a registered physician who can approve only through a bearer session. */
    public boolean registeredDoctor() {
        return registeredDoctor;
    }

    public SignRequestStatus status() {
        return status;
    }

    public boolean nonceConsumed() {
        return nonceConsumed;
    }

    public Instant approvedAt() {
        return approvedAt;
    }

    public String approvedBy() {
        return approvedBy;
    }

    public UUID signatureId() {
        return signatureId;
    }
}
