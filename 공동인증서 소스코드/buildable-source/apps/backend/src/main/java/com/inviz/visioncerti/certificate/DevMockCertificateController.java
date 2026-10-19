package com.inviz.visioncerti.certificate;

import com.inviz.visioncerti.certificate.CertificateDtos.CertificateSessionResponse;
import com.inviz.visioncerti.certificate.CertificateDtos.DevMockSignRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEVELOPMENT ONLY. Answers 404 DEV_MOCK_CERTIFICATE_DISABLED unless {@code VISIONCERTI_CERT_DEV_MOCK_ENABLED=true}.
 * Results are labeled MOCK_CERTIFICATE and carry no real trust.
 */
@RestController
@Profile("dev")
public class DevMockCertificateController {

    private final CertificateEnrollmentService certificates;

    public DevMockCertificateController(CertificateEnrollmentService certificates) {
        this.certificates = certificates;
    }

    @PostMapping("/v1/certificate-enrollment/dev/mock-sign")
    ResponseEntity<CertificateSessionResponse> mockSign(@RequestBody DevMockSignRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(certificates.devMockSign(body == null ? null : body.challengeId(),
                        body == null ? null : body.fingerprintSha256()));
    }
}
