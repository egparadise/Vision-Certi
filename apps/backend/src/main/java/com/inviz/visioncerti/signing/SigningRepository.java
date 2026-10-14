package com.inviz.visioncerti.signing;

import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.common.Hashing;
import com.inviz.visioncerti.store.Kind;
import com.inviz.visioncerti.store.Store;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * Durable, tenant-scoped store for sign requests and signatures. At most one PENDING_APPROVAL request exists per
 * (version, signer) and at most one signature per request: both are UNIQUE constraints, so concurrent instances
 * cannot create duplicates. Signatures are insert-only.
 */
@Repository
public class SigningRepository {

    static final Kind<SignRequest> REQUESTS = Kind.of("sign_requests", SignRequest.class)
            .id(SignRequest::id).tenant(SignRequest::tenantId)
            .column("document_version_id", r -> r.documentVersionId().toString())
            .column("signer_id", SignRequest::signerId)
            .column("created_by", SignRequest::createdBy)
            .unique("pending_key", r -> r.status() == SignRequestStatus.PENDING_APPROVAL
                    ? Hashing.sha256Hex(r.tenantId() + "\u0000" + r.documentVersionId() + "\u0000" + r.signerId())
                    : null,
                    () -> ApiException.conflict("SIGN_REQUEST_ALREADY_PENDING",
                            "A pending sign request already exists for this version and signer"))
            .build();

    static final Kind<Signature> SIGNATURES = Kind.of("signatures", Signature.class)
            .id(Signature::id).tenant(Signature::tenantId)
            .unique("sign_request_id", s -> s.signRequestId().toString(),
                    () -> ApiException.conflict("SIGNATURE_ALREADY_EXISTS",
                            "This sign request already has a signature"))
            .immutable()
            .build();

    private Store store;

    @Autowired
    public SigningRepository(Store store) {
        this.store = store;
    }

    /** Unit-test wiring: the store is attached by the first service constructed with it. */
    public SigningRepository() {
    }

    void attach(Store candidate) {
        if (store == null) {
            store = candidate;
        }
    }

    Store store() {
        if (store == null) {
            throw new IllegalStateException("SigningRepository has no store");
        }
        return store;
    }

    void insertRequest(SignRequest request) {
        store().run(tx -> tx.insert(REQUESTS, request));
    }

    /** Persists lifecycle changes of a request read in the current transaction. */
    void updateRequest(SignRequest request) {
        store().run(tx -> tx.update(REQUESTS, request));
    }

    Optional<SignRequest> findRequest(String tenantId, UUID id) {
        return store().tx(tx -> tx.get(REQUESTS, id)).filter(r -> r.tenantId().equals(tenantId));
    }

    /** Reads and row-locks a request; concurrent approvals of the same request serialize here. */
    Optional<SignRequest> lockRequest(String tenantId, UUID id) {
        return store().tx(tx -> tx.lockGet(REQUESTS, id)).filter(r -> r.tenantId().equals(tenantId));
    }

    /** Newest first. */
    List<SignRequest> listRequests(String tenantId) {
        List<SignRequest> result = new ArrayList<>(store().tx(tx -> tx.find(REQUESTS, Kind.TENANT, tenantId)));
        result.sort(Comparator.comparing(SignRequest::createdAt).reversed().thenComparing(SignRequest::id));
        return result;
    }

    void insertSignature(Signature signature) {
        store().run(tx -> tx.insert(SIGNATURES, signature));
    }

    public Optional<Signature> findSignature(String tenantId, UUID id) {
        return store().tx(tx -> tx.get(SIGNATURES, id)).filter(s -> s.tenantId().equals(tenantId));
    }

    public List<Signature> listSignatures(String tenantId) {
        List<Signature> result = new ArrayList<>(store().tx(tx -> tx.find(SIGNATURES, Kind.TENANT, tenantId)));
        result.sort(Comparator.comparing(Signature::createdAt).reversed().thenComparing(Signature::id));
        return result;
    }
}
