package com.inviz.visioncerti.certificate;

import com.inviz.visioncerti.certificate.CertificateDtos.CertificateSessionResponse;
import com.inviz.visioncerti.certificate.CertificateDtos.ChallengeResponse;
import com.inviz.visioncerti.certificate.CertificateDtos.ConfigResponse;
import com.inviz.visioncerti.certificate.CertificateDtos.EnrollmentChallengeRequest;
import com.inviz.visioncerti.certificate.CertificateDtos.EnrollmentResponse;
import com.inviz.visioncerti.certificate.CertificateDtos.LoginRequest;
import com.inviz.visioncerti.certificate.CertificateDtos.MobileCallbackResponse;
import com.inviz.visioncerti.certificate.CertificateDtos.MobileConsumeRequest;
import com.inviz.visioncerti.certificate.CertificateDtos.MobileStartRequest;
import com.inviz.visioncerti.certificate.CertificateDtos.MobileStartResponse;
import com.inviz.visioncerti.certificate.CertificateDtos.UnregisterRequest;
import com.inviz.visioncerti.certificate.CertificateDtos.VerifyRequest;
import com.inviz.visioncerti.common.ListResponse;
import com.inviz.visioncerti.doctor.DoctorDtos.ConsentRequest;
import com.inviz.visioncerti.doctor.DoctorPrincipal;
import com.inviz.visioncerti.doctor.DoctorService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Physician certificate enrollment and login. Public routes need no identity; enrollment management needs the
 * physician's bearer session. Dev identity headers are never consulted here.
 */
@RestController
@RequestMapping("/v1/certificate-enrollment")
public class CertificateEnrollmentController {

    private final CertificateEnrollmentService certificates;
    private final DoctorService doctors;

    public CertificateEnrollmentController(CertificateEnrollmentService certificates, DoctorService doctors) {
        this.certificates = certificates;
        this.doctors = doctors;
    }

    @GetMapping("/config")
    ConfigResponse config() {
        return certificates.config();
    }

    @PostMapping("/challenges")
    ResponseEntity<ChallengeResponse> challenge(@RequestBody(required = false) EnrollmentChallengeRequest body,
            HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        DoctorPrincipal principal = authorization == null ? null : doctors.authenticate(authorization);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(certificates.createEnrollmentChallenge(body == null ? null : body.invitationCode(), principal,
                        request.getRemoteAddr()));
    }

    @PostMapping("/verify")
    ResponseEntity<CertificateSessionResponse> verify(@RequestBody VerifyRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(certificates.verifyEnrollment(body));
    }

    @PostMapping("/login-challenges")
    ResponseEntity<ChallengeResponse> loginChallenge(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(certificates.createLoginChallenge(request.getRemoteAddr()));
    }

    @PostMapping("/login")
    ResponseEntity<CertificateSessionResponse> login(@RequestBody LoginRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(certificates.login(body));
    }

    @GetMapping("/enrollments")
    ListResponse<EnrollmentResponse> enrollments(DoctorPrincipal principal) {
        return ListResponse.of(certificates.listForDoctor(principal));
    }

    @PostMapping("/enrollments/{registrationId}/consent")
    EnrollmentResponse consent(DoctorPrincipal principal, @PathVariable String registrationId,
            @RequestBody ConsentRequest body) {
        return certificates.consent(principal, registrationId, body);
    }

    @PostMapping("/enrollments/{registrationId}/unregister")
    EnrollmentResponse unregister(DoctorPrincipal principal, @PathVariable String registrationId,
            @RequestBody(required = false) UnregisterRequest body) {
        return certificates.unregister(principal, registrationId, body == null ? null : body.reason());
    }

    @PostMapping("/mobile/transactions")
    ResponseEntity<MobileStartResponse> startMobile(@RequestBody MobileStartRequest body,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(certificates.startMobile(body == null ? null : body.purpose(),
                        body == null ? null : body.invitationCode(), request.getRemoteAddr()));
    }

    /** Provider → server. The raw body and headers go to the adapter, which must authenticate them. */
    @PostMapping("/mobile/transactions/{transactionId}/callback")
    MobileCallbackResponse mobileCallback(@PathVariable String transactionId,
            @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name.toLowerCase(java.util.Locale.ROOT), request.getHeader(name));
        }
        return certificates.mobileCallback(transactionId, headers, body);
    }

    @PostMapping("/mobile/transactions/{transactionId}/consume")
    ResponseEntity<CertificateSessionResponse> consumeMobile(@PathVariable String transactionId,
            @RequestBody MobileConsumeRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(certificates.consumeMobile(transactionId, body == null ? null : body.transactionSecret()));
    }
}
