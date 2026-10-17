package com.inviz.visioncerti.doctor;

import com.inviz.visioncerti.doctor.credential.DoctorCredential;
import java.time.Instant;
import java.util.UUID;

/**
 * Mutable physician record; changed only by {@link DoctorService} inside a storage transaction (row-locked read,
 * then a version-checked update). Stored as one {@code certi_rt.doctors} row.
 */
final class Doctor {

    enum InvitationState { ISSUED, USED, EXPIRED, LOCKED, VOID }

    final UUID id;
    final String tenantId;
    final String name;
    final String licenseNumber;
    final String centerName;
    final String googleEmail;
    final String identityEvidenceRef;
    final String licenseEvidenceRef;
    final Instant createdAt;
    final String createdBy;

    DoctorStatus status = DoctorStatus.PENDING;
    String identityMode;
    String identityIssuer;
    String identitySubject;
    Instant identityVerifiedAt;
    Instant consentedAt;
    String consentVersion;
    Instant activatedAt;
    String statusReason;
    Instant statusChangedAt;
    DoctorCredential credential;
    boolean credentialRevoked;
    boolean activationInProgress;
    /** Start of the current activation attempt; an attempt older than the activation lease may be retried. */
    Instant activationStartedAt;
    /** Optional SPKI SHA-256 recorded by the admin from previously verified evidence; enables automatic review. */
    String expectedCertificateFingerprint;

    String invitationHash;
    Instant invitationExpiresAt;
    InvitationState invitationState;
    int invitationFailures;
    int invitationChallenges;
    /** Durable HMAC-authenticated PACS account binding, never released on revocation. */
    String pacsAccountKey;
    /** Direct INVIZ signup is unverified until certificate proof, medical review and consent. */
    boolean selfRegisteredAccount;

    Doctor(UUID id, String tenantId, String name, String licenseNumber, String centerName, String googleEmail,
            String identityEvidenceRef, String licenseEvidenceRef, Instant createdAt, String createdBy) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.licenseNumber = licenseNumber;
        this.centerName = centerName;
        this.googleEmail = googleEmail;
        this.identityEvidenceRef = identityEvidenceRef;
        this.licenseEvidenceRef = licenseEvidenceRef;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    /** For the JSON codec only. */
    private Doctor() {
        this(null, null, null, null, null, null, null, null, null, null);
    }

    String bindingKey() {
        return identityIssuer == null ? null : identityIssuer + "|" + identitySubject;
    }

    @Override
    public String toString() {
        return "Doctor[id=" + id + ", tenantId=" + tenantId + ", status=" + status + ", <personal data redacted>]";
    }
}
