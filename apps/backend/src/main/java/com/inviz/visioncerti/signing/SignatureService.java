package com.inviz.visioncerti.signing;

import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.common.Hashing;
import com.inviz.visioncerti.document.DocumentRepository;
import com.inviz.visioncerti.document.DocumentVersion;
import com.inviz.visioncerti.security.RequestActor;
import com.inviz.visioncerti.signing.SigningDtos.VerificationResponse;
import com.inviz.visioncerti.signing.SigningDtos.CheckResult;
import com.inviz.visioncerti.signing.SigningDtos.VerificationChecks;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Signature listing and verification. Verification recomputes the stored document hash and checks the
 * signature value with the provider. The overall status is never {@code VALID}: no certificate chain,
 * revocation, timestamp or approval evidence exists in this slice, and mock values are not signatures.
 */
@Service
public class SignatureService {

    private final SigningRepository repository;
    private final DocumentRepository documents;
    private final SigningProvider signingProvider;
    private final AuditService audit;
    private final Clock clock;

    public SignatureService(SigningRepository repository, DocumentRepository documents,
            SigningProvider signingProvider, AuditService audit, Clock clock) {
        this.repository = repository;
        this.documents = documents;
        repository.attach(audit.store());
        documents.attach(audit.store());
        this.signingProvider = signingProvider;
        this.audit = audit;
        this.clock = clock;
    }

    public List<Signature> list(RequestActor actor) {
        return repository.listSignatures(actor.tenantId());
    }

    public VerificationResponse verify(RequestActor actor, UUID signatureId) {
        Signature signature = repository.findSignature(actor.tenantId(), signatureId)
                .orElseThrow(() -> ApiException.notFound("SIGNATURE_NOT_FOUND", "Signature not found"));
        List<String> reasons = new ArrayList<>();

        Optional<DocumentVersion> version = documents.findVersion(actor.tenantId(), signature.documentVersionId());
        boolean hashMatches = version.map(v -> Hashing.sha256Hex(v.content()).equals(signature.documentSha256()))
                .orElse(false);
        if (version.isEmpty()) {
            reasons.add("DOCUMENT_VERSION_UNAVAILABLE");
        } else if (!hashMatches) {
            reasons.add("DOCUMENT_HASH_MISMATCH");
        }

        if (signature.doctorEvidence() != null) {
            return verifyDoctorSignature(actor, signature, version, hashMatches, reasons);
        }

        SigningProvider.Check check;
        if (!signature.signatureMode().equals(signingProvider.signatureMode())) {
            check = SigningProvider.Check.UNVERIFIABLE;
            reasons.add("NO_PROVIDER_FOR_SIGNATURE_MODE");
        } else {
            check = signingProvider.verify(SignRequestService.SignaturePayload.of(signature.tenantId(),
                    signature.signRequestId(), signature.documentVersionId(), signature.documentSha256(),
                    signature.signerId()), signature.signatureValue());
            if (check == SigningProvider.Check.BROKEN) {
                reasons.add("SIGNATURE_VALUE_MISMATCH");
            }
        }

        CheckResult documentIntegrity = version.isEmpty() ? CheckResult.INDETERMINATE
                : hashMatches ? CheckResult.VALID : CheckResult.INVALID;
        // A mock value can only ever be INVALID (altered) or INDETERMINATE; never cryptographically VALID.
        CheckResult signatureCheck = check == SigningProvider.Check.BROKEN ? CheckResult.INVALID
                : CheckResult.INDETERMINATE;
        if (check == SigningProvider.Check.INTACT && !signature.clinicalUseAllowed()) {
            reasons.add("MOCK_SIGNATURE_INTEGRITY_OK");
        }
        if (!signature.clinicalUseAllowed()) {
            reasons.add(signature.signatureMode());
        }
        reasons.add("TRUST_CHAIN_NOT_AVAILABLE");
        reasons.add("MISSING_TRUSTED_TIMESTAMP");
        reasons.add("REVOCATION_EVIDENCE_NOT_AVAILABLE");
        reasons.add("AUTHORIZATION_EVIDENCE_NOT_VERIFIABLE");
        VerificationChecks checks = new VerificationChecks(documentIntegrity, signatureCheck,
                CheckResult.INDETERMINATE, CheckResult.INDETERMINATE, CheckResult.INDETERMINATE,
                CheckResult.INDETERMINATE);
        CheckResult status = documentIntegrity == CheckResult.INVALID || signatureCheck == CheckResult.INVALID
                ? CheckResult.INVALID
                : CheckResult.INDETERMINATE;

        audit.record(actor.tenantId(), actor.actorId(), "SIGNATURE_VERIFIED", "SIGNATURE", signature.id().toString(),
                AuditService.SUCCESS, Map.of("status", status.name()));
        return new VerificationResponse(signature.id(), status, signature.signatureMode(),
                signature.clinicalUseAllowed(), check == SigningProvider.Check.INTACT, checks, List.copyOf(reasons),
                clock.instant());
    }

    /**
     * Registered physician signature: a real ECDSA P-256 check over the stored bytes with the public key recorded at
     * signing. Even when it verifies, the overall status stays INDETERMINATE: the certificate comes from a private dev
     * CA (or is simulated), and there is no trusted timestamp or CAdES/PAdES container.
     */
    private VerificationResponse verifyDoctorSignature(RequestActor actor, Signature signature,
            Optional<DocumentVersion> version, boolean hashMatches, List<String> reasons) {
        DoctorSignatureEvidence evidence = signature.doctorEvidence();
        boolean verifies = version.isPresent() && SignRequestService.verifiesOver(evidence.publicKeySpki(),
                version.get().content(), decode(signature.signatureValue()));
        CheckResult documentIntegrity = version.isEmpty() ? CheckResult.INDETERMINATE
                : hashMatches ? CheckResult.VALID : CheckResult.INVALID;
        CheckResult signatureCheck;
        if (version.isEmpty()) {
            signatureCheck = CheckResult.INDETERMINATE;
        } else if (!verifies) {
            signatureCheck = CheckResult.INVALID;
            reasons.add("SIGNATURE_VALUE_MISMATCH");
        } else if (evidence.cryptographicallyAttributable()) {
            signatureCheck = CheckResult.VALID;
        } else {
            // Simulated keys produce real ECDSA math but prove nothing about who held the key.
            signatureCheck = CheckResult.INDETERMINATE;
            reasons.add("SIMULATED_KEY_SIGNATURE_INTACT");
        }
        // Dev identities (MOCK_IDENTITY, MOCK_CERTIFICATE) prove nothing about who approved.
        boolean mockIdentity = evidence.identityMode() == null || evidence.identityMode().startsWith("MOCK_");
        if (mockIdentity) {
            reasons.add("MOCK_IDENTITY");
        }
        reasons.add(signature.signatureMode());
        reasons.add("X509".equals(evidence.certificateKind()) ? "PRIVATE_CA_NOT_TRUSTED" : "SIMULATED_CERTIFICATE_NOT_X509");
        reasons.add("PRIVATE_PKI_NOT_TIMESTAMPED");
        reasons.add("MISSING_TRUSTED_TIMESTAMP");
        reasons.add("RAW_ECDSA_NOT_CADES");
        CheckResult revocation = evidence.revocationCheckedAtSigning() ? CheckResult.VALID : CheckResult.INDETERMINATE;
        if (revocation != CheckResult.VALID) {
            reasons.add("REVOCATION_EVIDENCE_NOT_AVAILABLE");
        }
        CheckResult authorization = evidence.cryptographicallyAttributable() && !mockIdentity ? CheckResult.VALID
                : CheckResult.INDETERMINATE;
        VerificationChecks checks = new VerificationChecks(documentIntegrity, signatureCheck,
                CheckResult.INDETERMINATE, CheckResult.INDETERMINATE, revocation, authorization);
        CheckResult status = documentIntegrity == CheckResult.INVALID || signatureCheck == CheckResult.INVALID
                ? CheckResult.INVALID
                : CheckResult.INDETERMINATE;
        audit.record(actor.tenantId(), actor.actorId(), "SIGNATURE_VERIFIED", "SIGNATURE", signature.id().toString(),
                AuditService.SUCCESS, Map.of("status", status.name()));
        return new VerificationResponse(signature.id(), status, signature.signatureMode(), false,
                verifies && !evidence.cryptographicallyAttributable(), checks, List.copyOf(reasons), clock.instant());
    }

    private static byte[] decode(String base64) {
        try {
            return java.util.Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }
}
