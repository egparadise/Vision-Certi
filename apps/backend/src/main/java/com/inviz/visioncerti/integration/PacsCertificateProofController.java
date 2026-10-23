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

/** Certificate proof with HMAC server exchange or S256-bound public PC exchange. */
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
                .body(proofs.start(body, PacsRequestContext.from(request)));
    }

    @PostMapping("/v1/pacs-certificate-proofs/verify")
    ResponseEntity<PacsCertificateProofService.ProofCodeResponse> verify(@RequestBody(required = false) LoginRequest body,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(proofs.verify(body, PacsRequestContext.from(request)));
    }

    @PostMapping("/v1/integrations/pacs/certificate-proofs/redeem")
    ResponseEntity<PacsCertificateProofService.Receipt> redeem(HttpServletRequest request,
            @RequestBody(required = false) PacsCertificateProofService.RedeemRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(proofs.redeem(client(request), body, PacsRequestContext.from(request)));
    }

    @GetMapping("/v1/integrations/pacs/certificate-proofs/{verificationId}")
    ResponseEntity<PacsCertificateProofService.Receipt> status(HttpServletRequest request,
            @PathVariable UUID verificationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(proofs.status(client(request), verificationId, PacsRequestContext.from(request)));
    }

    @PostMapping("/v1/integrations/pacs/certificate-proofs/{verificationId}/events")
    ResponseEntity<PacsCertificateProofService.ClientEventResponse> clientEvent(HttpServletRequest request,
            @PathVariable UUID verificationId,
            @RequestBody(required = false) PacsCertificateProofService.ClientEventRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(proofs.clientEvent(client(request), verificationId, body, PacsRequestContext.from(request)));
    }

    private static IntegrationClient client(HttpServletRequest request) {
        Object client = request.getAttribute(IntegrationAuthFilter.CLIENT_ATTRIBUTE);
        if (!(client instanceof IntegrationClient c)) throw IntegrationAuthFilter.authInvalid();
        return c;
    }

    @GetMapping("/v1/pacs-certificate-proofs/pc/capabilities")
    ResponseEntity<java.util.Map<String, Object>> pcCapabilities() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(java.util.Map.of("protocol", "VC-PC-PROOF-S256-V1", "codeChallengeMethod", "S256",
                        "clientEvents", true, "clientEventPath", "/v1/pacs-certificate-proofs/pc/events",
                        "accountLease", "VC-ACCOUNT-36H-V1", "accountLeaseHours", 36,
                        "heartbeatSeconds", 20, "ownerTimeoutSeconds", 90));
    }

    @PostMapping("/v1/pacs-certificate-proofs/pc/account/activate")
    ResponseEntity<PacsCertificateProofService.AccountStatus> activateAccount(HttpServletRequest request,
            @RequestBody PacsCertificateProofService.AccountActivateRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(proofs.activateAccount(body, PacsRequestContext.from(request)));
    }

    @PostMapping("/v1/pacs-certificate-proofs/pc/account/status")
    ResponseEntity<PacsCertificateProofService.AccountStatus> accountStatus(HttpServletRequest request,
            @RequestBody PacsCertificateProofService.AccountStatusRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(proofs.accountStatus(body, PacsRequestContext.from(request)));
    }

    @PostMapping("/v1/pacs-certificate-proofs/pc/account/heartbeat")
    ResponseEntity<PacsCertificateProofService.AccountStatus> accountHeartbeat(HttpServletRequest request,
            @RequestBody PacsCertificateProofService.AccountHeartbeatRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(proofs.heartbeatAccount(body, PacsRequestContext.from(request)));
    }

    @PostMapping("/v1/pacs-certificate-proofs/pc/account/events")
    ResponseEntity<PacsCertificateProofService.ClientEventResponse> accountEvent(HttpServletRequest request,
            @RequestBody PacsCertificateProofService.AccountEventRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(proofs.accountEvent(body, PacsRequestContext.from(request)));
    }

    @PostMapping("/v1/pacs-certificate-proofs/pc/redeem")
    ResponseEntity<PacsCertificateProofService.PcReceipt> redeemPc(HttpServletRequest request,
            @RequestBody(required = false) PacsCertificateProofService.PcRedeemRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(proofs.redeemPc(body, PacsRequestContext.from(request)));
    }

    @PostMapping("/v1/pacs-certificate-proofs/pc/status")
    ResponseEntity<PacsCertificateProofService.Receipt> statusPc(HttpServletRequest request,
            @RequestBody(required = false) PacsCertificateProofService.PcStatusRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(proofs.statusPc(body, PacsRequestContext.from(request)));
    }

    @PostMapping("/v1/pacs-certificate-proofs/pc/events")
    ResponseEntity<PacsCertificateProofService.ClientEventResponse> clientEventPc(HttpServletRequest request,
            @RequestBody(required = false) PacsCertificateProofService.PcClientEventRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(proofs.clientEventPc(body, PacsRequestContext.from(request)));
    }
}
