package com.inviz.visioncerti.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inviz.visioncerti.MutableClock;
import com.inviz.visioncerti.audit.AuditEvent;
import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.common.Hashing;
import com.inviz.visioncerti.delivery.DeliveryService;
import com.inviz.visioncerti.document.DocumentDtos.DocumentMetadata;
import com.inviz.visioncerti.document.DocumentDtos.DocumentResponse;
import com.inviz.visioncerti.document.DocumentRepository;
import com.inviz.visioncerti.document.DocumentService;
import com.inviz.visioncerti.document.DocumentVersion;
import com.inviz.visioncerti.security.ActorDirectory;
import com.inviz.visioncerti.security.ActorRole;
import com.inviz.visioncerti.security.RequestActor;
import com.inviz.visioncerti.signing.SigningDtos.ApproveSignRequest;
import com.inviz.visioncerti.signing.SigningDtos.CheckResult;
import com.inviz.visioncerti.signing.SigningDtos.CreateSignRequest;
import com.inviz.visioncerti.signing.SigningDtos.SignRequestResponse;
import com.inviz.visioncerti.signing.SigningDtos.VerificationResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Service-level rules of the signing lifecycle, wired without Spring for a controllable clock. */
class SigningFlowTest {

    private static final String TENANT = "tenant-x";
    private static final RequestActor DOCTOR = new RequestActor(TENANT, "doctor-1", ActorRole.DOCTOR);
    private static final RequestActor OTHER_DOCTOR = new RequestActor(TENANT, "doctor-2", ActorRole.DOCTOR);
    private static final RequestActor ADMIN = new RequestActor(TENANT, "admin-1", ActorRole.ADMIN);

    private final ActorDirectory directory = new ActorDirectory() {
        private final Map<String, Member> members = Map.of(
                "doctor-1", new Member("doctor-1", "Doctor 1", ActorRole.DOCTOR),
                "doctor-2", new Member("doctor-2", "Doctor 2", ActorRole.DOCTOR),
                "admin-1", new Member("admin-1", "Admin 1", ActorRole.ADMIN));

        @Override
        public List<Tenant> tenants() {
            return List.of(new Tenant(TENANT, "Tenant X"));
        }

        @Override
        public Optional<Member> member(String tenantId, String actorId) {
            return TENANT.equals(tenantId) ? Optional.ofNullable(members.get(actorId)) : Optional.empty();
        }
    };

    private MutableClock clock;
    private AuditService audit;
    private DocumentRepository documentRepository;
    private DocumentService documents;
    private SigningRepository signingRepository;
    private MockSigningProvider provider;
    private SignRequestService signRequests;
    private SignatureService signatures;
    private DeliveryService deliveries;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-13T00:00:00Z"));
        audit = new AuditService(clock);
        documentRepository = new DocumentRepository();
        documents = new DocumentService(documentRepository, directory, audit, clock, 1_000_000);
        signingRepository = new SigningRepository();
        provider = new MockSigningProvider();
        deliveries = new DeliveryService(audit, clock);
        signRequests = new SignRequestService(signingRepository, documentRepository, directory, provider, deliveries,
                audit, clock, Duration.ofMinutes(10));
        signatures = new SignatureService(signingRepository, documentRepository, provider, audit, clock);
    }

    private static byte[] pdf(String body) {
        return ("%PDF-1.7\n" + body + "\n%%EOF").getBytes(StandardCharsets.US_ASCII);
    }

    private DocumentResponse upload(String externalId, byte[] content) {
        return documents.register(ADMIN, new DocumentMetadata(externalId, "study-1", "Report", "doctor-1"), content)
                .document();
    }

    private SignRequestResponse request(DocumentResponse doc) {
        return signRequests.create(ADMIN, new CreateSignRequest(doc.latestVersion().id(), "doctor-1"));
    }

    private String nonceOf(SignRequestResponse r) {
        return signRequests.get(DOCTOR, r.id()).nonce();
    }

    private static String code(Throwable t) {
        return ((ApiException) t).code();
    }

    @Test
    void approvalBindsToExactHashAndCompletesWithMockSignature() {
        DocumentResponse doc = upload("doc-1", pdf("a"));
        SignRequestResponse req = request(doc);
        assertThat(req.documentSha256()).isEqualTo(Hashing.sha256Hex(pdf("a")));
        assertThat(req.expiresAt()).isEqualTo(Instant.parse("2026-10-13T00:10:00Z"));

        SignRequestResponse approved = signRequests.approve(DOCTOR, req.id(),
                new ApproveSignRequest(req.documentSha256(), nonceOf(req)));

        assertThat(approved.status()).isEqualTo(SignRequestStatus.COMPLETED);
        assertThat(approved.signatureMode()).isEqualTo("MOCK_NOT_VALID_FOR_CLINICAL_USE");
        assertThat(approved.nonce()).isNull();
        Signature signature = signatures.list(DOCTOR).getFirst();
        assertThat(signature.id()).isEqualTo(approved.signatureId());
        assertThat(signature.clinicalUseAllowed()).isFalse();
        assertThat(deliveries.list(TENANT)).hasSize(1);
    }

    @Test
    void hashMismatchIsRejected() {
        SignRequestResponse req = request(upload("doc-1", pdf("a")));
        assertThatThrownBy(() -> signRequests.approve(DOCTOR, req.id(),
                new ApproveSignRequest(Hashing.sha256Hex(pdf("b")), nonceOf(req))))
                .satisfies(t -> assertThat(code(t)).isEqualTo("DOCUMENT_HASH_MISMATCH"));
        assertThat(signatures.list(DOCTOR)).isEmpty();
    }

    @Test
    void nonceCannotBeReplayedAfterApproval() {
        SignRequestResponse req = request(upload("doc-1", pdf("a")));
        String nonce = nonceOf(req);
        signRequests.approve(DOCTOR, req.id(), new ApproveSignRequest(req.documentSha256(), nonce));

        assertThatThrownBy(() -> signRequests.approve(DOCTOR, req.id(),
                new ApproveSignRequest(req.documentSha256(), nonce)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("NONCE_ALREADY_USED"));
        assertThat(signatures.list(DOCTOR)).hasSize(1);
        assertThat(deliveries.list(TENANT)).hasSize(1);
    }

    @Test
    void wrongNonceIsRejectedWithoutConsumingTheRequest() {
        SignRequestResponse req = request(upload("doc-1", pdf("a")));
        assertThatThrownBy(() -> signRequests.approve(DOCTOR, req.id(),
                new ApproveSignRequest(req.documentSha256(), "forged-nonce")))
                .satisfies(t -> assertThat(code(t)).isEqualTo("NONCE_INVALID"));
        assertThat(signRequests.get(DOCTOR, req.id()).status()).isEqualTo(SignRequestStatus.PENDING_APPROVAL);

        assertThat(signRequests.approve(DOCTOR, req.id(), new ApproveSignRequest(req.documentSha256(), nonceOf(req)))
                .status()).isEqualTo(SignRequestStatus.COMPLETED);
    }

    @Test
    void expiredRequestCannotBeApproved() {
        SignRequestResponse req = request(upload("doc-1", pdf("a")));
        String nonce = nonceOf(req);
        clock.advance(Duration.ofMinutes(10));

        assertThatThrownBy(() -> signRequests.approve(DOCTOR, req.id(),
                new ApproveSignRequest(req.documentSha256(), nonce)))
                .satisfies(t -> {
                    assertThat(code(t)).isEqualTo("SIGN_REQUEST_EXPIRED");
                    assertThat(((ApiException) t).status().value()).isEqualTo(410);
                });
        assertThat(signRequests.get(DOCTOR, req.id()).status()).isEqualTo(SignRequestStatus.EXPIRED);
        assertThat(signatures.list(DOCTOR)).isEmpty();
    }

    @Test
    void requestIsStillApprovableJustBeforeExpiry() {
        SignRequestResponse req = request(upload("doc-1", pdf("a")));
        String nonce = nonceOf(req);
        clock.advance(Duration.ofMinutes(10).minusSeconds(1));
        assertThat(signRequests.approve(DOCTOR, req.id(), new ApproveSignRequest(req.documentSha256(), nonce))
                .status()).isEqualTo(SignRequestStatus.COMPLETED);
    }

    @Test
    void adminCanNeverApproveOrSignAndDenialIsAudited() {
        SignRequestResponse req = request(upload("doc-1", pdf("a")));
        String nonce = nonceOf(req);

        assertThatThrownBy(() -> signRequests.approve(ADMIN, req.id(),
                new ApproveSignRequest(req.documentSha256(), nonce)))
                .satisfies(t -> {
                    assertThat(code(t)).isEqualTo("SIGNING_NOT_PERMITTED_FOR_ROLE");
                    assertThat(((ApiException) t).status().value()).isEqualTo(403);
                });
        assertThat(signatures.list(ADMIN)).isEmpty();
        assertThat(signRequests.get(ADMIN, req.id()).status()).isEqualTo(SignRequestStatus.PENDING_APPROVAL);
        AuditEvent denial = audit.list(TENANT).getFirst();
        assertThat(denial.action()).isEqualTo("SIGN_REQUEST_APPROVAL_DENIED");
        assertThat(denial.outcome()).isEqualTo(AuditService.DENIED);
        assertThat(denial.actorId()).isEqualTo("admin-1");
    }

    @Test
    void onlyTheBoundSignerSeesTheNonceAndCanApprove() {
        SignRequestResponse req = request(upload("doc-1", pdf("a")));
        assertThat(req.nonce()).as("creator is admin").isNull();
        assertThat(signRequests.get(OTHER_DOCTOR, req.id()).nonce()).isNull();
        String nonce = nonceOf(req);
        assertThat(nonce).isNotBlank();

        assertThatThrownBy(() -> signRequests.approve(OTHER_DOCTOR, req.id(),
                new ApproveSignRequest(req.documentSha256(), nonce)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("SIGNER_MISMATCH"));
    }

    @Test
    void newVersionDoesNotAlterOldVersionAndSupersedesPendingRequest() {
        byte[] v1Bytes = pdf("version one");
        DocumentResponse doc = upload("doc-1", v1Bytes);
        DocumentVersion v1 = documentRepository.findVersion(TENANT, doc.latestVersion().id()).orElseThrow();
        SignRequestResponse reqV1 = request(doc);
        String nonce = nonceOf(reqV1);

        // Mutating arrays handed in or out never reaches the stored version.
        v1Bytes[10] = 'X';
        v1.content()[10] = 'Y';
        DocumentResponse afterV2 = documents.addVersion(ADMIN, doc.id(), v1.id(), "addendum", pdf("version two"));

        assertThat(afterV2.versions()).hasSize(2);
        assertThat(afterV2.versions().getFirst().sha256()).isEqualTo(Hashing.sha256Hex(pdf("version one")));
        assertThat(Hashing.sha256Hex(v1.content())).isEqualTo(v1.sha256());
        assertThat(afterV2.latestVersion().previousVersionId()).isEqualTo(v1.id());

        assertThatThrownBy(() -> signRequests.approve(DOCTOR, reqV1.id(),
                new ApproveSignRequest(reqV1.documentSha256(), nonce)))
                .satisfies(t -> assertThat(code(t)).isEqualTo("DOCUMENT_VERSION_SUPERSEDED"));
        assertThatThrownBy(() -> signRequests.create(ADMIN, new CreateSignRequest(v1.id(), "doctor-1")))
                .satisfies(t -> assertThat(code(t)).isEqualTo("DOCUMENT_VERSION_SUPERSEDED"));
        assertThatThrownBy(() -> documents.addVersion(ADMIN, doc.id(), v1.id(), "stale", pdf("version three")))
                .satisfies(t -> assertThat(code(t)).isEqualTo("DOCUMENT_VERSION_SUPERSEDED"));

        SignRequestResponse reqV2 = request(afterV2);
        assertThat(reqV2.documentSha256()).isEqualTo(Hashing.sha256Hex(pdf("version two")));
    }

    @Test
    void reRegisteringSameExternalIdWithDifferentBytesConflicts() {
        upload("doc-1", pdf("a"));
        assertThat(documents.register(ADMIN, new DocumentMetadata("doc-1", "study-1", "Report", "doctor-1"),
                pdf("a")).created()).isFalse();
        assertThatThrownBy(() -> upload("doc-1", pdf("b")))
                .satisfies(t -> assertThat(code(t)).isEqualTo("DOCUMENT_VERSION_CONFLICT"));
    }

    @Test
    void verificationNeverReportsValidForMockSignatures() {
        SignRequestResponse req = request(upload("doc-1", pdf("a")));
        SignRequestResponse done = signRequests.approve(DOCTOR, req.id(),
                new ApproveSignRequest(req.documentSha256(), nonceOf(req)));

        VerificationResponse result = signatures.verify(DOCTOR, done.signatureId());

        assertThat(result.status()).isEqualTo(CheckResult.INDETERMINATE);
        assertThat(result.checks().documentIntegrity()).isEqualTo(CheckResult.VALID);
        assertThat(result.checks().signature()).isEqualTo(CheckResult.INDETERMINATE);
        assertThat(result.mockSignatureIntact()).isTrue();
        assertThat(result.clinicalUseAllowed()).isFalse();
        assertThat(result.reasonCodes()).contains("MOCK_NOT_VALID_FOR_CLINICAL_USE");
    }

    @Test
    void verificationWithADifferentKeyReportsInvalid() {
        SignRequestResponse req = request(upload("doc-1", pdf("a")));
        SignRequestResponse done = signRequests.approve(DOCTOR, req.id(),
                new ApproveSignRequest(req.documentSha256(), nonceOf(req)));
        SignatureService otherKey = new SignatureService(signingRepository, documentRepository,
                new MockSigningProvider(), audit, clock);

        VerificationResponse result = otherKey.verify(DOCTOR, done.signatureId());

        assertThat(result.status()).isEqualTo(CheckResult.INVALID);
        assertThat(result.checks().signature()).isEqualTo(CheckResult.INVALID);
        assertThat(result.reasonCodes()).contains("SIGNATURE_VALUE_MISMATCH");
    }

    @Test
    void auditChainLinksEachEventToThePreviousOne() {
        SignRequestResponse req = request(upload("doc-1", pdf("a")));
        String nonce = nonceOf(req);
        signRequests.approve(DOCTOR, req.id(), new ApproveSignRequest(req.documentSha256(), nonce));
        List<AuditEvent> oldestFirst = audit.list(TENANT).reversed();
        assertThat(oldestFirst.getFirst().previousHash()).isEqualTo("0".repeat(64));
        for (int i = 1; i < oldestFirst.size(); i++) {
            assertThat(oldestFirst.get(i).previousHash()).isEqualTo(oldestFirst.get(i - 1).hash());
        }
        assertThat(oldestFirst).extracting(AuditEvent::action).containsSubsequence(
                "DOCUMENT_VERSION_REGISTERED", "SIGN_REQUEST_CREATED", "SIGN_REQUEST_APPROVED", "SIGNATURE_CREATED",
                "DELIVERY_QUEUED");
        assertThat(oldestFirst).allSatisfy(e -> assertThat(e.details().values()).doesNotContain(nonce));
    }
}
