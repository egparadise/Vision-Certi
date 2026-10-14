package com.inviz.visioncerti.signing;

import com.inviz.visioncerti.common.ListResponse;
import com.inviz.visioncerti.security.RequestActor;
import com.inviz.visioncerti.signing.SigningDtos.VerificationResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/signatures")
public class SignatureController {

    private final SignatureService signatureService;

    public SignatureController(SignatureService signatureService) {
        this.signatureService = signatureService;
    }

    @GetMapping
    public ListResponse<Signature> list(RequestActor actor) {
        return ListResponse.of(signatureService.list(actor));
    }

    /** Read-only check; no Idempotency-Key needed (it only appends an audit event). */
    @PostMapping("/{id}/verify")
    public VerificationResponse verify(RequestActor actor, @PathVariable UUID id) {
        return signatureService.verify(actor, id);
    }
}
