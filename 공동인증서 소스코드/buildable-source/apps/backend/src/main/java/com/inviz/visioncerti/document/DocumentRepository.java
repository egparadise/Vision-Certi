package com.inviz.visioncerti.document;

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
 * Durable document store. Every lookup requires the tenant; a record of another tenant is reported as absent so
 * callers cannot distinguish "other tenant" from "does not exist". Versions are separate insert-only rows and their
 * bytes immutable blobs; the document row keeps the version list (metadata only) and is updated with a version check.
 */
@Repository
public class DocumentRepository {

    static final Kind<Document> DOCUMENTS = Kind.of("documents", Document.class)
            .id(Document::id).tenant(Document::tenantId)
            .unique("external_key", d -> externalKey(d.tenantId(), d.externalDocumentId()),
                    () -> ApiException.conflict("DOCUMENT_REGISTERED_CONCURRENTLY",
                            "The document was registered concurrently; retry the request"))
            .build();

    static final Kind<DocumentVersion> VERSIONS = Kind.of("document_versions", DocumentVersion.class)
            .id(DocumentVersion::id).tenant(DocumentVersion::tenantId)
            .column("document_id", v -> v.documentId().toString())
            .immutable()
            .build();

    private Store store;

    @Autowired
    public DocumentRepository(Store store) {
        this.store = store;
    }

    /** Unit-test wiring: the store is attached by the first service constructed with it. */
    public DocumentRepository() {
    }

    public void attach(Store candidate) {
        if (store == null) {
            store = candidate;
        }
    }

    Store store() {
        if (store == null) {
            throw new IllegalStateException("DocumentRepository has no store");
        }
        return store;
    }

    /** Inserts a new document with its first version, or updates it with an appended version. */
    void save(Document document, DocumentVersion newVersion, boolean created) {
        store().run(tx -> {
            if (created) {
                tx.insert(DOCUMENTS, document);
            } else {
                tx.update(DOCUMENTS, document);
            }
            tx.insert(VERSIONS, newVersion);
            tx.putBlob(newVersion.id(), newVersion.tenantId(), newVersion.rawContent());
        });
    }

    public Optional<Document> find(String tenantId, UUID id) {
        return store().tx(tx -> tx.get(DOCUMENTS, id)).filter(d -> d.tenantId().equals(tenantId));
    }

    /** Reads and row-locks the document (append-version paths). */
    Optional<Document> lockFind(String tenantId, UUID id) {
        return store().tx(tx -> tx.lockGet(DOCUMENTS, id)).filter(d -> d.tenantId().equals(tenantId));
    }

    Optional<Document> findByExternalId(String tenantId, String externalDocumentId) {
        return store().tx(tx -> tx.lockFindOne(DOCUMENTS, "external_key", externalKey(tenantId, externalDocumentId)))
                .filter(d -> d.tenantId().equals(tenantId));
    }

    /** The version with its exact stored bytes. */
    public Optional<DocumentVersion> findVersion(String tenantId, UUID versionId) {
        return store().tx(tx -> tx.get(VERSIONS, versionId)
                .filter(v -> v.tenantId().equals(tenantId))
                .flatMap(v -> tx.blob(v.id(), tenantId).map(v::withContent)));
    }

    /** Newest first. */
    public List<Document> list(String tenantId) {
        List<Document> result = new ArrayList<>(store().tx(tx -> tx.find(DOCUMENTS, Kind.TENANT, tenantId)));
        result.sort(Comparator.comparing(Document::updatedAt).reversed().thenComparing(Document::id));
        return result;
    }

    public long versionCount(String tenantId) {
        return store().tx(tx -> tx.count(VERSIONS, Kind.TENANT, tenantId, null));
    }

    private static String externalKey(String tenantId, String externalDocumentId) {
        return Hashing.sha256Hex(tenantId + "\u0000" + externalDocumentId);
    }
}
