package com.inviz.visioncerti.document;

import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.common.Hashing;
import com.inviz.visioncerti.common.IdempotencyService;
import com.inviz.visioncerti.common.ListResponse;
import com.inviz.visioncerti.document.DocumentDtos.DocumentMetadata;
import com.inviz.visioncerti.document.DocumentDtos.DocumentResponse;
import com.inviz.visioncerti.security.RequestActor;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/v1/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;

    public DocumentController(DocumentService documentService, IdempotencyService idempotency,
            ObjectMapper objectMapper) {
        this.documentService = documentService;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    /**
     * Multipart upload: part {@code metadata} (JSON, any part content type) and part {@code file} (PDF bytes).
     * 201 when created, 200 when the identical registration already exists, 409 when the
     * externalDocumentId exists with different bytes or metadata.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> register(RequestActor actor,
            @RequestPart("metadata") String metadataJson,
            @RequestPart("file") MultipartFile file,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey)
            throws IOException {
        DocumentMetadata metadata = parse(metadataJson);
        byte[] content = file.getBytes();
        String fingerprint = Hashing.sha256Hex(String.join("\u0000", String.valueOf(metadata.externalDocumentId()),
                String.valueOf(metadata.externalStudyId()), String.valueOf(metadata.title()),
                String.valueOf(metadata.signerId()), Hashing.sha256Hex(content)));
        return idempotency.execute(actor, "POST /v1/documents", idempotencyKey, fingerprint, () -> {
            DocumentService.Registration registration = documentService.register(actor, metadata, content);
            return ResponseEntity.status(registration.created() ? HttpStatus.CREATED : HttpStatus.OK)
                    .body(registration.document());
        });
    }

    /**
     * Multipart: part {@code file} (PDF) plus form fields {@code previousVersionId} (must be the latest
     * version) and {@code amendmentReason}. 201 with the full document including the new version.
     */
    @PostMapping(path = "/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> addVersion(RequestActor actor, @PathVariable UUID id,
            @RequestPart("file") MultipartFile file,
            @RequestParam("previousVersionId") UUID previousVersionId,
            @RequestParam("amendmentReason") String amendmentReason,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey)
            throws IOException {
        byte[] content = file.getBytes();
        String fingerprint = Hashing.sha256Hex(String.join("\u0000", id.toString(), previousVersionId.toString(),
                amendmentReason, Hashing.sha256Hex(content)));
        return idempotency.execute(actor, "POST /v1/documents/{id}/versions", idempotencyKey, fingerprint,
                () -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(documentService.addVersion(actor, id, previousVersionId, amendmentReason, content)));
    }

    @GetMapping
    public ListResponse<DocumentResponse> list(RequestActor actor) {
        return ListResponse.of(documentService.list(actor));
    }

    @GetMapping("/{id}")
    public DocumentResponse get(RequestActor actor, @PathVariable UUID id) {
        return documentService.get(actor, id);
    }

    /** Exact stored bytes of one version (the same object a sign request is bound to). */
    @GetMapping("/{id}/versions/{versionId}/content")
    public ResponseEntity<byte[]> content(RequestActor actor, @PathVariable UUID id, @PathVariable UUID versionId) {
        DocumentVersion version = documentService.getVersion(actor, id, versionId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Document-Sha256", version.sha256())
                .body(version.content());
    }

    private DocumentMetadata parse(String json) {
        try {
            DocumentMetadata metadata = objectMapper.readValue(json, DocumentMetadata.class);
            if (metadata == null) {
                throw ApiException.unprocessable("VALIDATION_FAILED", "metadata is required");
            }
            return metadata;
        } catch (JacksonException e) {
            throw ApiException.badRequest("MALFORMED_REQUEST", "metadata part must be a JSON object");
        }
    }
}
