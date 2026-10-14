package com.inviz.visioncerti.signing;

import com.inviz.visioncerti.common.Hashing;
import com.inviz.visioncerti.common.IdempotencyService;
import com.inviz.visioncerti.common.ListResponse;
import com.inviz.visioncerti.security.RequestActor;
import com.inviz.visioncerti.signing.SigningDtos.ApproveSignRequest;
import com.inviz.visioncerti.signing.SigningDtos.CreateSignRequest;
import com.inviz.visioncerti.signing.SigningDtos.SignRequestResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/sign-requests")
public class SignRequestController {

    private final SignRequestService signRequestService;
    private final IdempotencyService idempotency;

    public SignRequestController(SignRequestService signRequestService, IdempotencyService idempotency) {
        this.signRequestService = signRequestService;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<SignRequestResponse> create(RequestActor actor, @Valid @RequestBody CreateSignRequest body,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        String fingerprint = Hashing.sha256Hex(body.documentVersionId() + "\u0000" + body.signerId());
        return idempotency.execute(actor, "POST /v1/sign-requests", idempotencyKey, fingerprint,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(signRequestService.create(actor, body)));
    }

    @GetMapping
    public ListResponse<SignRequestResponse> list(RequestActor actor) {
        return ListResponse.of(signRequestService.list(actor));
    }

    @GetMapping("/{id}")
    public SignRequestResponse get(RequestActor actor, @PathVariable UUID id) {
        return signRequestService.get(actor, id);
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<SignRequestResponse> approve(RequestActor actor, @PathVariable UUID id,
            @Valid @RequestBody ApproveSignRequest body,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        String fingerprint = Hashing.sha256Hex(id + "\u0000" + body.documentSha256() + "\u0000" + body.nonce());
        return idempotency.execute(actor, "POST /v1/sign-requests/{id}/approve", idempotencyKey, fingerprint,
                () -> ResponseEntity.accepted().body(signRequestService.approve(actor, id, body)));
    }
}
