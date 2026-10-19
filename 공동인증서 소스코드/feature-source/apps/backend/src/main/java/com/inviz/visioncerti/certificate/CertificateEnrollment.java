package com.inviz.visioncerti.certificate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One certificate (or mobile) enrollment. Proof fields are final; only lifecycle fields change, inside a storage
 * transaction of {@link CertificateEnrollmentService} (row-locked read, version-checked update). Records are never deleted or overwritten: a replacement is a new
 * enrollment and the old one becomes REVOKED (SUPERSEDED).
 */
final class CertificateEnrollment {

    enum Kind { PC_CERTIFICATE, MOBILE_CERTIFICATE, MOCK_CERTIFICATE }

    enum RegistrationStatus { PENDING, ACTIVE, REVOKED }

    enum ReviewStatus { PENDING, APPROVED, REJECTED }

    final UUID registrationId;
    final UUID doctorId;
    final String tenantId;
    final Kind kind;
    final String fingerprintSha256;
    final String publicKeySha256;
    final String subjectDn;
    final String issuerDn;
    final String serialNumber;
    final Instant notBefore;
    final Instant notAfter;
    final String signatureAlgorithm;
    final String keyAlgorithm;
    /** DER (base64) of the leaf and client-supplied intermediates, kept for re-validation; null for mobile. */
    final String certificateDer;
    final List<String> chainDer;
    final String provider;
    final String providerSubject;
    final Instant createdAt;

    RegistrationStatus registrationStatus = RegistrationStatus.PENDING;
    CertificateValidator.RevocationStatus externalStatus;
    Instant externalCheckedAt;
    ReviewStatus reviewStatus = ReviewStatus.PENDING;
    String reviewMethod;
    String reviewedBy;
    Instant reviewedAt;
    String reviewEvidenceRef;
    String reviewNote;
    Instant consentedAt;
    Instant activatedAt;
    Instant revokedAt;
    String revocationReason;
    UUID supersedes;
    /** Null for historical records; only new PACS registrations require these declarations. */
    Boolean medicalCredentialsRequired;
    String medicalLicenseNumber;
    Boolean specialist;
    String specialistQualificationNumber;
    String specialty;
    Instant medicalCredentialsSubmittedAt;

    CertificateEnrollment(UUID registrationId, UUID doctorId, String tenantId, Kind kind, String fingerprintSha256,
            String publicKeySha256, String subjectDn, String issuerDn, String serialNumber, Instant notBefore,
            Instant notAfter, String signatureAlgorithm, String keyAlgorithm, String certificateDer, List<String> chainDer,
            String provider, String providerSubject, Instant createdAt) {
        this.registrationId = registrationId;
        this.doctorId = doctorId;
        this.tenantId = tenantId;
        this.kind = kind;
        this.fingerprintSha256 = fingerprintSha256;
        this.publicKeySha256 = publicKeySha256;
        this.subjectDn = subjectDn;
        this.issuerDn = issuerDn;
        this.serialNumber = serialNumber;
        this.notBefore = notBefore;
        this.notAfter = notAfter;
        this.signatureAlgorithm = signatureAlgorithm;
        this.keyAlgorithm = keyAlgorithm;
        this.certificateDer = certificateDer;
        this.chainDer = chainDer == null ? List.of() : List.copyOf(chainDer);
        this.provider = provider;
        this.providerSubject = providerSubject;
        this.createdAt = createdAt;
    }

    /** For the JSON codec only. */
    private CertificateEnrollment() {
        this(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null);
    }

    boolean live() {
        return registrationStatus != RegistrationStatus.REVOKED;
    }

    CertificateDtos.EnrollmentResponse toResponse() {
        return new CertificateDtos.EnrollmentResponse(registrationId, doctorId, tenantId, kind.name(),
                registrationStatus.name(), externalStatus == null ? null : externalStatus.name(), externalCheckedAt,
                reviewStatus.name(), reviewMethod, reviewedBy, reviewedAt, consentedAt, fingerprintSha256,
                publicKeySha256, subjectDn,
                issuerDn, serialNumber, notBefore, notAfter, signatureAlgorithm, keyAlgorithm, provider,
                providerSubject, createdAt, activatedAt, revokedAt, revocationReason, supersedes,
                medicalCredentialsRequired, medicalLicenseNumber, specialist, specialistQualificationNumber,
                specialty, medicalCredentialsSubmittedAt);
    }

    @Override
    public String toString() {
        return "CertificateEnrollment[" + registrationId + ", " + kind + ", " + registrationStatus + "]";
    }
}
