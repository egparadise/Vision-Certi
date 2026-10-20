package com.inviz.visioncerti.integration;

import com.inviz.visioncerti.certificate.CertificateDtos.ChallengeResponse;
import com.inviz.visioncerti.certificate.CertificateDtos.LoginRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Public certificate challenge/proof, followed by PACS-only HMAC authenticated code redemption and status. */
@RestController
public final class PacsCertificateProofController {
    private final PacsCertificateProofService proofs;

    public PacsCertificateProofController(PacsCertificateProofService proofs) {
        this.proofs = proofs;
    }

    @PostMapping("/v1/pacs-certificate-proofs/challenges")
    ResponseEntity<ChallengeResponse> start(@RequestBody(required = false) PacsCertificateProofService.StartRequest body,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(proofs.start(body, request.getRemoteAddr()));
    }

    @PostMapping("/v1/pacs-certificate-proofs/verify")
    ResponseEntity<PacsCertificateProofService.ProofCodeResponse> verify(@RequestBody(required = false) LoginRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(proofs.verify(body));
    }

    @PostMapping("/v1/integrations/pacs/certificate-proofs/redeem")
    ResponseEntity<PacsCertificateProofService.Receipt> redeem(HttpServletRequest request,
            @RequestBody(required = false) PacsCertificateProofService.RedeemRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(proofs.redeem(client(request), body));
    }

    @GetMapping("/v1/integrations/pacs/certificate-proofs/{verificationId}")
    ResponseEntity<PacsCertificateProofService.Receipt> status(HttpServletRequest request,
            @PathVariable UUID verificationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(proofs.status(client(request), verificationId));
    }

    private static IntegrationClient client(HttpServletRequest request) {
        Object client = request.getAttribute(IntegrationAuthFilter.CLIENT_ATTRIBUTE);
        if (!(client instanceof IntegrationClient c)) throw IntegrationAuthFilter.authInvalid();
        return c;
    }
}
