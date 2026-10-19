package com.inviz.visioncerti.signing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request/response shapes for {@code /v1/sign-requests} and {@code /v1/signatures}. */
public final class SigningDtos {

    private SigningDtos() {
    }

    public record CreateSignRequest(@NotNull UUID documentVersionId, @NotBlank @Size(max = 128) String signerId) {
    }

    /**
     * {@code reviewAttestation} is required (value {@link #REVIEWED_EXACT_REPORT_CONTENT}) when the bound version is
     * a PACS report snapshot; it is ignored for PDFs.
     */
    public record ApproveSignRequest(
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}", message = "must be 64 lowercase hex characters")
            String documentSha256,
            @NotBlank @Size(max = 128) String nonce,
            @Size(max = 64) String reviewAttestation,
            @Size(max = 131072) String certificateDerBase64,
            @Size(max = 32768) String externalSignatureBase64,
            @Size(max = 32) String externalSignatureAlgorithm) {

        public static final String REVIEWED_EXACT_REPORT_CONTENT = "REVIEWED_EXACT_REPORT_CONTENT";

        public ApproveSignRequest(String documentSha256, String nonce) {
            this(documentSha256, nonce, null, null, null, null);
        }

        public ApproveSignRequest(String documentSha256, String nonce, String reviewAttestation) {
            this(documentSha256, nonce, reviewAttestation, null, null, null);
        }
    }

    public record SignRequestResponse(
            UUID id,
            String tenantId,
            UUID documentId,
            UUID documentVersionId,
            String documentSha256,
            String signerId,
            String nonce,
            SignRequestStatus status,
            String signatureMode,
            Instant createdAt,
            String createdBy,
            Instant expiresAt,
            Instant approvedAt,
            String approvedBy,
            UUID signatureId) {

        static SignRequestResponse from(SignRequest r, String signatureMode, boolean showNonce) {
            return new SignRequestResponse(r.id(), r.tenantId(), r.documentId(), r.documentVersionId(),
                    r.documentSha256(), r.signerId(), showNonce ? r.nonce() : null, r.status(), signatureMode,
                    r.createdAt(), r.createdBy(), r.expiresAt(), r.approvedAt(), r.approvedBy(), r.signatureId());
        }
    }

    /** Contract values. {@code VALID} is never produced by this slice (no PKI, TSA or HSM evidence). */
    public enum CheckResult {
        VALID,
        INVALID,
        INDETERMINATE
    }

    public record VerificationChecks(
            CheckResult documentIntegrity,
            CheckResult signature,
            CheckResult trustChain,
            CheckResult timestamp,
            CheckResult revocationAtSigning,
            CheckResult authorizationAtSigning) {
    }

    /**
     * {@code documentIntegrity} may be VALID (stored bytes still match the bound SHA-256); that is a
     * separate judgement from signature validity. For mock signatures {@code status} is at best
     * INDETERMINATE and {@code mockSignatureIntact} reports whether the mock value still matches.
     */
    public record VerificationResponse(
            UUID signatureId,
            CheckResult status,
            String signatureMode,
            boolean clinicalUseAllowed,
            boolean mockSignatureIntact,
            VerificationChecks checks,
            List<String> reasonCodes,
            Instant verifiedAt) {
    }
}
