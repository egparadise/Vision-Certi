package com.inviz.visioncerti.signing;

import com.inviz.visioncerti.admin.SettingKey;
import com.inviz.visioncerti.admin.SystemSettings;
import com.inviz.visioncerti.audit.AuditEvent;
import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.common.Hashing;
import com.inviz.visioncerti.delivery.DeliveryService;
import com.inviz.visioncerti.document.Document;
import com.inviz.visioncerti.document.DocumentRepository;
import com.inviz.visioncerti.document.DocumentService;
import com.inviz.visioncerti.document.DocumentVersion;
import com.inviz.visioncerti.security.ActorDirectory;
import com.inviz.visioncerti.security.RequestActor;
import com.inviz.visioncerti.signing.RegisteredDoctorSigner.SignerSnapshot;
import com.inviz.visioncerti.signing.SigningDtos.ApproveSignRequest;
import com.inviz.visioncerti.signing.SigningDtos.CreateSignRequest;
import com.inviz.visioncerti.signing.SigningDtos.SignRequestResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Sign request lifecycle: create (bound to exact version + hash), explicit doctor approval with one-time
 * nonce, then synchronous signing and delivery queueing.
 *
 * <p>Two signer kinds exist. Dev synthetic doctors (dev headers) get the legacy MOCK HMAC signature. Registered
 * physicians approve only through {@link #approveAsRegisteredDoctor} with their bearer session and sign with their
 * own provisioned ECDSA key.
 *
 * <p>State is durable ({@link SigningRepository}). Approval consumes the one-time nonce under the request's row
 * lock in its own transaction (so two concurrent approvals of one request cannot both proceed, on any instance);
 * the remote signing call runs outside any transaction; the signature is stored in a second transaction that
 * requires the request to still be SIGNING, and the UNIQUE (sign_request_id) constraint forbids a second logical
 * signature. A request stuck in SIGNING longer than {@link #SIGNING_TIMEOUT} (process crash) becomes FAILED.
 */
@Service
public class SignRequestService {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** A request still SIGNING after this long was interrupted (crash/restart) and can never complete. */
    static final Duration SIGNING_TIMEOUT = Duration.ofMinutes(5);

    private final SigningRepository repository;
    private final DocumentRepository documents;
    private final ActorDirectory directory;
    private final RegisteredSigners registeredSigners;
    private final SigningProvider signingProvider;
    private final DeliveryService deliveries;
    private final AuditService audit;
    private final Clock clock;
    private final Duration requestTtl;
    private final com.inviz.visioncerti.store.Store store;

    public SignRequestService(SigningRepository repository, DocumentRepository documents, ActorDirectory directory,
            SigningProvider signingProvider, DeliveryService deliveries, AuditService audit, Clock clock,
            Duration requestTtl) {
        this(repository, documents, directory, RegisteredSigners.NONE, signingProvider, deliveries, audit, clock,
                requestTtl);
    }

    @Autowired
    public SignRequestService(SigningRepository repository, DocumentRepository documents, ActorDirectory directory,
            RegisteredSigners registeredSigners, SigningProvider signingProvider, DeliveryService deliveries,
            AuditService audit, Clock clock, @Value("${visioncerti.sign-requests.ttl:PT10M}") Duration requestTtl) {
        this.repository = repository;
        this.documents = documents;
        repository.attach(audit.store());
        documents.attach(audit.store());
        this.store = audit.store();
        this.directory = directory;
        this.registeredSigners = registeredSigners;
        this.signingProvider = signingProvider;
        this.deliveries = deliveries;
        this.audit = audit;
        this.clock = clock;
        this.requestTtl = requestTtl;
    }

    private volatile SystemSettings settings;

    /** Administrator overrides ({@link SystemSettings}); absent in unit tests that construct the service directly. */
    @Autowired(required = false)
    public void useSettings(SystemSettings settings) {
        this.settings = settings;
    }

    private Duration requestTtl() {
        SystemSettings s = settings;
        return s == null ? requestTtl : s.duration(SettingKey.SIGN_REQUEST_TTL);
    }

    public SignRequestResponse create(RequestActor actor, CreateSignRequest body) {
        return store.tx(tx -> {
            if (body.documentVersionId() != null && body.signerId() != null) {
                tx.lock("sign-request|" + actor.tenantId() + "|" + body.documentVersionId() + "|" + body.signerId());
            }
            return createLocked(actor, body);
        });
    }

    private SignRequestResponse createLocked(RequestActor actor, CreateSignRequest body) {
        DocumentVersion version = documents.findVersion(actor.tenantId(), body.documentVersionId())
                .orElseThrow(() -> ApiException.notFound("DOCUMENT_VERSION_NOT_FOUND", "Document version not found"));
        Document document = documents.find(actor.tenantId(), version.documentId())
                .orElseThrow(() -> ApiException.notFound("DOCUMENT_NOT_FOUND", "Document not found"));
        if (!document.latestVersion().id().equals(version.id())) {
            throw ApiException.conflict("DOCUMENT_VERSION_SUPERSEDED", "A newer version of this document exists");
        }
        boolean devDoctor = directory.isDoctorInTenant(actor.tenantId(), body.signerId());
        boolean registeredDoctor = !devDoctor && registeredSigners.isActiveSigner(actor.tenantId(), body.signerId());
        if (!devDoctor && !registeredDoctor) {
            throw ApiException.unprocessable("SIGNER_NOT_ELIGIBLE", "signerId is not an active doctor of this tenant");
        }
        if (!body.signerId().equals(document.signerId())) {
            throw ApiException.unprocessable("SIGNER_NOT_ASSIGNED", "signerId is not the signer assigned to this document");
        }
        boolean pendingExists = repository.listRequests(actor.tenantId()).stream()
                .peek(this::expireIfDue)
                .anyMatch(r -> r.documentVersionId().equals(version.id())
                        && r.signerId().equals(body.signerId())
                        && r.status() == SignRequestStatus.PENDING_APPROVAL);
        if (pendingExists) {
            throw ApiException.conflict("SIGN_REQUEST_ALREADY_PENDING",
                    "A pending sign request already exists for this version and signer");
        }
        Instant now = clock.instant();
        SignRequest request = new SignRequest(UUID.randomUUID(), actor.tenantId(), document.id(), version.id(),
                version.sha256(), body.signerId(), newNonce(), now, actor.actorId(), now.plus(requestTtl()),
                registeredDoctor);
        repository.insertRequest(request);
        audit.record(actor.tenantId(), actor.actorId(), "SIGN_REQUEST_CREATED", "SIGN_REQUEST",
                request.id().toString(), AuditService.SUCCESS, Map.of(
                        "documentVersionId", version.id().toString(),
                        "documentSha256", version.sha256(),
                        "signerId", body.signerId(),
                        "signerKind", registeredDoctor ? "REGISTERED_DOCTOR" : "DEV_SYNTHETIC",
                        "expiresAt", request.expiresAt().toString()));
        return toResponse(request, actor);
    }

    public SignRequestResponse get(RequestActor actor, UUID id) {
        return store.tx(tx -> {
            SignRequest request = find(actor.tenantId(), id);
            expireIfDue(request);
            return toResponse(request, actor);
        });
    }

    public List<SignRequestResponse> list(RequestActor actor) {
        return store.tx(tx -> repository.listRequests(actor.tenantId()).stream()
                .peek(this::expireIfDue)
                .map(r -> toResponse(r, actor))
                .toList());
    }

    /** A registered physician's own requests; the nonce is shown only to them while approval is possible. */
    public List<SignRequestResponse> listForDoctor(String tenantId, String doctorId) {
        return store.tx(tx -> repository.listRequests(tenantId).stream()
                .filter(r -> r.registeredDoctor() && r.signerId().equals(doctorId))
                .peek(this::expireIfDue)
                .map(r -> toDoctorResponse(r, doctorId))
                .toList());
    }

    public SignRequestResponse getForDoctor(String tenantId, String doctorId, UUID id) {
        return store.tx(tx -> {
            SignRequest request = find(tenantId, id);
            if (!request.registeredDoctor() || !request.signerId().equals(doctorId)) {
                throw ApiException.notFound("SIGN_REQUEST_NOT_FOUND", "Sign request not found");
            }
            expireIfDue(request);
            return toDoctorResponse(request, doctorId);
        });
    }

    /** Exact bytes of the version bound to a request, for the bound physician to read before approving. */
    public record BoundDocument(UUID documentVersionId, String sha256, String contentType, byte[] content) {
    }

    public BoundDocument documentForDoctor(String tenantId, String doctorId, UUID id) {
        return store.tx(tx -> documentForDoctorTx(tenantId, doctorId, id));
    }

    /** The exact bytes the physician's registered external certificate must sign for a PACS approval. */
    public String reportApprovalPayloadForDoctor(String tenantId, String doctorId, UUID id) {
        return store.tx(tx -> {
            SignRequest request = find(tenantId, id);
            if (!request.registeredDoctor() || !request.signerId().equals(doctorId)) {
                throw ApiException.notFound("SIGN_REQUEST_NOT_FOUND", "Sign request not found");
            }
            if (expireIfDue(request) || request.status() != SignRequestStatus.PENDING_APPROVAL) {
                throw ApiException.conflict("SIGN_REQUEST_NOT_PENDING", "Sign request is not pending approval");
            }
            DocumentVersion version = documents.findVersion(tenantId, request.documentVersionId())
                    .orElseThrow(() -> ApiException.notFound("DOCUMENT_VERSION_NOT_FOUND", "Document version not found"));
            if (!DocumentService.PACS_REPORT_CONTENT_TYPE.equals(version.contentType())
                    || !Hashing.sha256Hex(version.content()).equals(request.documentSha256())) {
                throw ApiException.conflict("REPORT_VERSION_INVALID", "The bound report version is unavailable or changed");
            }
            Document document = documents.find(tenantId, request.documentId())
                    .orElseThrow(() -> ApiException.notFound("DOCUMENT_NOT_FOUND", "Document not found"));
            if (!document.latestVersion().id().equals(version.id())) {
                throw ApiException.conflict("DOCUMENT_VERSION_SUPERSEDED", "A newer report version exists");
            }
            return Base64.getEncoder().encodeToString(ReportCertificateApproval.payload(request, document));
        });
    }

    private BoundDocument documentForDoctorTx(String tenantId, String doctorId, UUID id) {
        SignRequest request = find(tenantId, id);
        if (!request.registeredDoctor() || !request.signerId().equals(doctorId)) {
            throw ApiException.notFound("SIGN_REQUEST_NOT_FOUND", "Sign request not found");
        }
        DocumentVersion version = documents.findVersion(tenantId, request.documentVersionId())
                .orElseThrow(() -> ApiException.notFound("DOCUMENT_VERSION_NOT_FOUND", "Document version not found"));
        byte[] content = version.content();
        if (!Hashing.sha256Hex(content).equals(request.documentSha256())) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORED_DOCUMENT_CORRUPTED",
                    "Stored document does not match its recorded hash");
        }
        audit.record(tenantId, doctorId, "DOCUMENT_VIEWED_BY_SIGNER", "SIGN_REQUEST", request.id().toString(),
                AuditService.SUCCESS, Map.of("documentVersionId", request.documentVersionId().toString(),
                        "documentSha256", request.documentSha256()));
        return new BoundDocument(request.documentVersionId(), request.documentSha256(), version.contentType(),
                content);
    }

    /**
     * Explicit approval by the assigned dev synthetic doctor (legacy MOCK path). Order of checks: tenant scope,
     * role (admins can never sign), signer identity, expiry, nonce replay, state, version currency, hash binding,
     * nonce match. Every refusal is audited as DENIED.
     */
    public SignRequestResponse approve(RequestActor actor, UUID id, ApproveSignRequest body) {
        return store.tx(tx -> approveLocked(actor, id, body));
    }

    private SignRequestResponse approveLocked(RequestActor actor, UUID id, ApproveSignRequest body) {
        SignRequest request = lock(actor.tenantId(), id);
        if (!actor.isDoctor()) {
            throw deny(actor.tenantId(), actor.actorId(), actor.role().name(), request,
                    ApiException.forbidden("SIGNING_NOT_PERMITTED_FOR_ROLE",
                            "Only the assigned doctor can approve and sign; administrators cannot sign"));
        }
        if (request.registeredDoctor() || !actor.actorId().equals(request.signerId())
                || !directory.isDoctorInTenant(actor.tenantId(), actor.actorId())) {
            throw deny(actor.tenantId(), actor.actorId(), actor.role().name(), request,
                    ApiException.forbidden("SIGNER_MISMATCH", "Only the signer bound to this request can approve it"));
        }
        checkApprovable(actor.tenantId(), actor.actorId(), actor.role().name(), request, body);

        Instant now = clock.instant();
        request.markApproved(now, actor.actorId());
        audit.record(actor.tenantId(), actor.actorId(), "SIGN_REQUEST_APPROVED", "SIGN_REQUEST",
                request.id().toString(), AuditService.SUCCESS, Map.of("documentSha256", request.documentSha256()));

        request.markSigning();
        Signature signature = new Signature(UUID.randomUUID(), request.tenantId(), request.id(), request.documentId(),
                request.documentVersionId(), request.documentSha256(), request.signerId(),
                signingProvider.signatureMode(), signingProvider.algorithm(),
                signingProvider.sign(SignaturePayload.of(request.tenantId(), request.id(), request.documentVersionId(),
                        request.documentSha256(), request.signerId())),
                signingProvider.clinicalUseAllowed(), clock.instant(), null);
        return complete(actor.tenantId(), actor.actorId(), request, signature);
    }

    /**
     * Approval by a registered physician through their bearer session. Besides the legacy checks it requires,
     * every time, that the physician is ACTIVE in this tenant with a valid, unrevoked certificate, then signs the
     * SHA-256 of the exact stored bytes with the physician's pinned key and verifies the result before storing it.
     */
    public SignRequestResponse approveAsRegisteredDoctor(RegisteredDoctorSigner signer, UUID id,
            ApproveSignRequest body) {
        String tenantId = signer.tenantId();
        String doctorId = signer.doctorId();
        SignRequest found = find(tenantId, id);
        if (!found.registeredDoctor() || !found.signerId().equals(doctorId)) {
            throw deny(tenantId, doctorId, "REGISTERED_DOCTOR", found,
                    ApiException.forbidden("SIGNER_MISMATCH", "Only the signer bound to this request can approve it"));
        }
        // Certificate re-validation and remote revocation checks: network I/O, so outside any transaction.
        SignerSnapshot snapshot;
        try {
            snapshot = signer.assertCanSign();
        } catch (ApiException e) {
            throw deny(tenantId, doctorId, "REGISTERED_DOCTOR", found, e);
        }

        // 1. Consume the nonce under the row lock and move to SIGNING (committed before the remote call).
        Approved approved = store.tx(tx -> {
            SignRequest request = lock(tenantId, id);
            if (!request.registeredDoctor() || !request.signerId().equals(doctorId)) {
                throw deny(tenantId, doctorId, "REGISTERED_DOCTOR", request, ApiException.forbidden("SIGNER_MISMATCH",
                        "Only the signer bound to this request can approve it"));
            }
            byte[] content = checkApprovable(tenantId, doctorId, "REGISTERED_DOCTOR", request, body);
            DocumentVersion boundVersion = documents.findVersion(tenantId, request.documentVersionId())
                    .orElseThrow(() -> ApiException.notFound("DOCUMENT_VERSION_NOT_FOUND", "Document version not found"));
            boolean pacsReport = DocumentService.PACS_REPORT_CONTENT_TYPE.equals(boundVersion.contentType());
            if (pacsReport) {
                Document boundDocument = documents.find(tenantId, request.documentId())
                        .orElseThrow(() -> ApiException.notFound("DOCUMENT_NOT_FOUND", "Document not found"));
                try {
                    ReportCertificateApproval.verify(request, boundDocument, body,
                            snapshot.certificateEnrollmentFingerprint());
                } catch (ApiException e) {
                    throw deny(tenantId, doctorId, "REGISTERED_DOCTOR", request, e);
                }
            }
            Instant now = clock.instant();
            UUID approvalId = UUID.randomUUID();
            request.markApproved(now, doctorId);
            AuditEvent approval = audit.record(tenantId, doctorId, "SIGN_REQUEST_APPROVED", "SIGN_REQUEST",
                    request.id().toString(), AuditService.SUCCESS, Map.of(
                            "documentSha256", request.documentSha256(),
                            "approvalId", approvalId.toString(),
                            "certificateId", snapshot.certificateId(),
                            "keyName", snapshot.keyName(),
                            "keyVersion", Integer.toString(snapshot.keyVersion())));
            request.markSigning();
            repository.updateRequest(request);
            return new Approved(content, approvalId, approval.id(), pacsReport,
                    pacsReport ? Base64.getEncoder().encodeToString(ReportCertificateApproval.payload(request,
                            documents.find(tenantId, request.documentId()).orElseThrow())) : null);
        });

        // 2. Remote signature (outside any transaction), then the post-signing re-checks.
        byte[] content = approved.content();
        byte[] digest = Hashing.sha256(content);
        byte[] der;
        try {
            der = signer.signDigest(snapshot, digest);
        } catch (ApiException e) {
            throw fail(tenantId, doctorId, id, e);
        }
        try {
            signer.confirmStillAuthorized(snapshot);
        } catch (ApiException e) {
            // Status changed while the backend was signing: the signature value is discarded.
            throw fail(tenantId, doctorId, id, e);
        }
        if (!verifiesOver(snapshot.publicKeySpki(), content, der)) {
            throw fail(tenantId, doctorId, id, new ApiException(HttpStatus.BAD_GATEWAY, "SIGNATURE_SELF_CHECK_FAILED",
                    "The signing backend returned a signature that does not verify with the certificate key"));
        }

        // 3. Store the single logical signature, only if the request is still SIGNING.
        return store.tx(tx -> {
            SignRequest request = lock(tenantId, id);
            if (request.status() != SignRequestStatus.SIGNING) {
                throw deny(tenantId, doctorId, "REGISTERED_DOCTOR", request, ApiException.conflict(
                        "SIGNING_INTERRUPTED", "The request is no longer SIGNING; the signature value was discarded"));
            }
            Instant signedAt = clock.instant();
            Document document = documents.find(tenantId, request.documentId())
                    .orElseThrow(() -> ApiException.notFound("DOCUMENT_NOT_FOUND", "Document not found"));
            DoctorSignatureEvidence evidence = new DoctorSignatureEvidence(doctorId, snapshot.identityMode(),
                    snapshot.identityIssuer(), snapshot.identitySubject(), snapshot.certificateId(),
                    snapshot.certificateKind(), snapshot.keyName(), snapshot.keyVersion(), approved.approvalId(),
                    document.externalDocumentId(), request.documentVersionId(), request.documentSha256(), signedAt,
                    approved.approvalAuditId(), DoctorSignatureEvidence.FORMAT, snapshot.signingBackend(),
                    snapshot.cryptographicallyAttributable(), snapshot.revocationChecked(), snapshot.publicKeySpki(),
                    snapshot.certificateEnrollmentId(), snapshot.certificateEnrollmentFingerprint(),
                    approved.pacsReport() ? body.certificateDerBase64() : null,
                    approved.pacsReport() ? body.externalSignatureBase64() : null,
                    approved.pacsReport() ? body.externalSignatureAlgorithm() : null,
                    approved.externalPayloadBase64());
            Signature signature = new Signature(UUID.randomUUID(), tenantId, request.id(), request.documentId(),
                    request.documentVersionId(), request.documentSha256(), doctorId, snapshot.signatureMode(),
                    "ECDSA_P256_SHA256", Base64.getEncoder().encodeToString(der), false, signedAt, evidence);
            return complete(tenantId, doctorId, request, signature);
        });
    }

    /** Result of the approval transaction: the exact bytes to sign and the approval identifiers. */
    private record Approved(byte[] content, UUID approvalId, UUID approvalAuditId, boolean pacsReport,
            String externalPayloadBase64) {
    }

    /** Marks a SIGNING request FAILED after a remote/post-check failure and returns the audited refusal. */
    private ApiException fail(String tenantId, String doctorId, UUID id, ApiException e) {
        return store.tx(tx -> {
            SignRequest request = lock(tenantId, id);
            if (request.status() == SignRequestStatus.SIGNING) {
                request.markFailed();
                repository.updateRequest(request);
            }
            return deny(tenantId, doctorId, "REGISTERED_DOCTOR", request, e);
        });
    }

    private SignRequestResponse complete(String tenantId, String actorId, SignRequest request, Signature signature) {
        repository.insertSignature(signature);
        request.markCompleted(signature.id());
        repository.updateRequest(request);
        audit.record(tenantId, actorId, "SIGNATURE_CREATED", "SIGNATURE", signature.id().toString(),
                AuditService.SUCCESS, Map.of(
                        "signRequestId", request.id().toString(),
                        "signatureMode", signature.signatureMode(),
                        "algorithm", signature.algorithm()));
        deliveries.enqueue(tenantId, actorId, signature.id(), request.id(), request.documentId());
        return request.registeredDoctor() ? toDoctorResponse(request, actorId)
                : SignRequestResponse.from(request, signingProvider.signatureMode(), false);
    }

    // ------------------------------------------------------------------ service integrations (PACS)

    /** Sign request plus (when completed) its signature, as seen by the integration client that created it. */
    public record IntegrationView(SignRequestResponse request, Signature signature) {
    }

    /**
     * Creates (or returns the existing PENDING) registered-physician request for the latest version of an
     * integration document. The nonce is never returned here: only the bound physician sees it.
     */
    public IntegrationView createForIntegration(String tenantId, String actorId, UUID documentId,
            UUID documentVersionId, String signerId) {
        return store.tx(tx -> {
            tx.lock("sign-request|" + tenantId + "|" + documentVersionId + "|" + signerId);
            return createForIntegrationLocked(tenantId, actorId, documentId, documentVersionId, signerId);
        });
    }

    private IntegrationView createForIntegrationLocked(String tenantId, String actorId, UUID documentId,
            UUID documentVersionId, String signerId) {
        Document document = documents.find(tenantId, documentId)
                .orElseThrow(() -> ApiException.notFound("DOCUMENT_NOT_FOUND", "Document not found"));
        DocumentVersion version = documents.findVersion(tenantId, documentVersionId)
                .orElseThrow(() -> ApiException.notFound("DOCUMENT_VERSION_NOT_FOUND", "Document version not found"));
        if (!document.latestVersion().id().equals(version.id()) || !document.signerId().equals(signerId)) {
            throw ApiException.conflict("DOCUMENT_VERSION_SUPERSEDED", "Only the latest version can be signed");
        }
        if (!registeredSigners.isActiveSigner(tenantId, signerId)) {
            throw ApiException.unprocessable("SIGNER_NOT_ELIGIBLE", "signerId is not an active doctor of this tenant");
        }
        for (SignRequest existing : repository.listRequests(tenantId)) {
            expireIfDue(existing);
            if (existing.documentVersionId().equals(version.id()) && existing.signerId().equals(signerId)
                    && existing.createdBy().equals(actorId)
                    && existing.status() == SignRequestStatus.PENDING_APPROVAL) {
                return new IntegrationView(SignRequestResponse.from(existing, modeOf(existing), false), null);
            }
        }
        Instant now = clock.instant();
        SignRequest request = new SignRequest(UUID.randomUUID(), tenantId, document.id(), version.id(),
                version.sha256(), signerId, newNonce(), now, actorId, now.plus(requestTtl()), true);
        repository.insertRequest(request);
        audit.record(tenantId, actorId, "SIGN_REQUEST_CREATED", "SIGN_REQUEST", request.id().toString(),
                AuditService.SUCCESS, Map.of(
                        "documentVersionId", version.id().toString(),
                        "documentSha256", version.sha256(),
                        "signerId", signerId,
                        "signerKind", "REGISTERED_DOCTOR",
                        "source", "INTEGRATION",
                        "expiresAt", request.expiresAt().toString()));
        return new IntegrationView(SignRequestResponse.from(request, modeOf(request), false), null);
    }

    /** Status and evidence of a request created by {@code actorId}; other requests are reported as absent. */
    public IntegrationView getForIntegration(String tenantId, String actorId, UUID id) {
        return store.tx(tx -> {
            SignRequest request = repository.findRequest(tenantId, id)
                    .filter(r -> r.createdBy().equals(actorId))
                    .orElseThrow(() -> ApiException.notFound("SIGN_REQUEST_NOT_FOUND", "Sign request not found"));
            expireIfDue(request);
            Signature signature = request.signatureId() == null ? null
                    : repository.findSignature(tenantId, request.signatureId()).orElse(null);
            return new IntegrationView(SignRequestResponse.from(request, modeOf(request), false), signature);
        });
    }

    /**
     * Shared approval checks after signer identity: expiry, nonce replay, state, version currency, hash binding,
     * nonce match, then re-hash of the stored bytes. Returns the exact bytes to sign.
     */
    private byte[] checkApprovable(String tenantId, String actorId, String role, SignRequest request,
            ApproveSignRequest body) {
        if (expireIfDue(request) || request.status() == SignRequestStatus.EXPIRED) {
            throw deny(tenantId, actorId, role, request, new ApiException(HttpStatus.GONE, "SIGN_REQUEST_EXPIRED",
                    "Sign request expired; create a new request"));
        }
        if (request.nonceConsumed()) {
            throw deny(tenantId, actorId, role, request, ApiException.conflict("NONCE_ALREADY_USED",
                    "This approval nonce was already used"));
        }
        if (request.status() != SignRequestStatus.PENDING_APPROVAL) {
            throw deny(tenantId, actorId, role, request, ApiException.conflict("INVALID_STATE",
                    "Sign request is " + request.status() + ", not PENDING_APPROVAL"));
        }
        Document document = documents.find(tenantId, request.documentId())
                .orElseThrow(() -> ApiException.notFound("DOCUMENT_NOT_FOUND", "Document not found"));
        if (!document.latestVersion().id().equals(request.documentVersionId())) {
            throw deny(tenantId, actorId, role, request, ApiException.conflict("DOCUMENT_VERSION_SUPERSEDED",
                    "A newer document version exists; create a new request for it"));
        }
        if (!Hashing.constantTimeEquals(request.documentSha256(), body.documentSha256())) {
            throw deny(tenantId, actorId, role, request, ApiException.conflict("DOCUMENT_HASH_MISMATCH",
                    "documentSha256 does not match the version bound to this request"));
        }
        if (!Hashing.constantTimeEquals(request.nonce(), body.nonce())) {
            throw deny(tenantId, actorId, role, request, ApiException.conflict("NONCE_INVALID",
                    "nonce does not match this request"));
        }
        DocumentVersion version = documents.findVersion(tenantId, request.documentVersionId())
                .orElseThrow(() -> ApiException.notFound("DOCUMENT_VERSION_NOT_FOUND", "Document version not found"));
        if (DocumentService.PACS_REPORT_CONTENT_TYPE.equals(version.contentType())
                && !ApproveSignRequest.REVIEWED_EXACT_REPORT_CONTENT.equals(body.reviewAttestation())) {
            throw deny(tenantId, actorId, role, request, ApiException.unprocessable("REVIEW_ATTESTATION_REQUIRED",
                    "reviewAttestation must be REVIEWED_EXACT_REPORT_CONTENT for a report snapshot"));
        }
        byte[] content = version.content();
        if (!Hashing.sha256Hex(content).equals(request.documentSha256())) {
            // Stored bytes no longer match the bound hash: never sign.
            request.markFailed();
            repository.updateRequest(request);
            throw deny(tenantId, actorId, role, request, new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "STORED_DOCUMENT_CORRUPTED", "Stored document does not match its recorded hash"));
        }
        return content;
    }

    static boolean verifiesOver(String publicKeySpkiBase64, byte[] content, byte[] derSignature) {
        try {
            PublicKey key = KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeySpkiBase64)));
            java.security.Signature verifier = java.security.Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(content);
            return verifier.verify(derSignature);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private SignRequest find(String tenantId, UUID id) {
        return repository.findRequest(tenantId, id)
                .orElseThrow(() -> ApiException.notFound("SIGN_REQUEST_NOT_FOUND", "Sign request not found"));
    }

    /** Row-locked read; callers are inside a storage transaction. */
    private SignRequest lock(String tenantId, UUID id) {
        return repository.lockRequest(tenantId, id)
                .orElseThrow(() -> ApiException.notFound("SIGN_REQUEST_NOT_FOUND", "Sign request not found"));
    }

    /**
     * Moves a due PENDING_APPROVAL request to EXPIRED (returns true), and a request left SIGNING past
     * {@link #SIGNING_TIMEOUT} to FAILED. Callers are inside a storage transaction that read {@code request}.
     */
    private boolean expireIfDue(SignRequest request) {
        Instant now = clock.instant();
        if (request.status() == SignRequestStatus.PENDING_APPROVAL && !now.isBefore(request.expiresAt())) {
            request.markExpired();
            repository.updateRequest(request);
            audit.record(request.tenantId(), "system", "SIGN_REQUEST_EXPIRED", "SIGN_REQUEST",
                    request.id().toString(), AuditService.SUCCESS, Map.of("expiresAt", request.expiresAt().toString()));
            return true;
        }
        if (request.status() == SignRequestStatus.SIGNING && request.approvedAt() != null
                && !now.isBefore(request.approvedAt().plus(SIGNING_TIMEOUT))) {
            request.markFailed();
            repository.updateRequest(request);
            audit.record(request.tenantId(), "system", "SIGN_REQUEST_SIGNING_INTERRUPTED", "SIGN_REQUEST",
                    request.id().toString(), AuditService.SUCCESS, Map.of());
        }
        return false;
    }

    private ApiException deny(String tenantId, String actorId, String role, SignRequest request, ApiException e) {
        audit.record(tenantId, actorId, "SIGN_REQUEST_APPROVAL_DENIED", "SIGN_REQUEST",
                request.id().toString(), AuditService.DENIED, Map.of("code", e.code(), "role", role));
        return e;
    }

    /** The nonce is disclosed only to the bound signer while approval is still possible. */
    private SignRequestResponse toResponse(SignRequest request, RequestActor actor) {
        boolean showNonce = !request.registeredDoctor() && actor.isDoctor()
                && actor.actorId().equals(request.signerId())
                && request.status() == SignRequestStatus.PENDING_APPROVAL;
        return SignRequestResponse.from(request, modeOf(request), showNonce);
    }

    private SignRequestResponse toDoctorResponse(SignRequest request, String doctorId) {
        boolean showNonce = request.signerId().equals(doctorId)
                && request.status() == SignRequestStatus.PENDING_APPROVAL;
        return SignRequestResponse.from(request, modeOf(request), showNonce);
    }

    private String modeOf(SignRequest request) {
        if (!request.registeredDoctor()) {
            return signingProvider.signatureMode();
        }
        if (request.signatureId() != null) {
            return repository.findSignature(request.tenantId(), request.signatureId())
                    .map(Signature::signatureMode).orElse(registeredSigners.signatureMode());
        }
        return registeredSigners.signatureMode();
    }

    private static String newNonce() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Canonical bytes covered by a legacy mock signature value. */
    static final class SignaturePayload {

        private SignaturePayload() {
        }

        static byte[] of(String tenantId, UUID signRequestId, UUID documentVersionId, String documentSha256,
                String signerId) {
            return String.join("|", "vision-certi-mock-signature-v1", tenantId, signRequestId.toString(),
                    documentVersionId.toString(), documentSha256, signerId).getBytes(StandardCharsets.UTF_8);
        }
    }
}
