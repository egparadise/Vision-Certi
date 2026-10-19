package com.inviz.visioncerti.certificate;

import com.inviz.visioncerti.certificate.CertificateDtos.EnrollmentResponse;
import com.inviz.visioncerti.certificate.CertificateDtos.ReviewRequest;
import com.inviz.visioncerti.common.ListResponse;
import com.inviz.visioncerti.security.RequestActor;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin view and review of certificate enrollments (dev header identity, ADMIN, tenant-scoped). */
@RestController
@RequestMapping("/v1/admin/doctors/{doctorId}")
public class AdminCertificateController {

    private final CertificateEnrollmentService certificates;

    public AdminCertificateController(CertificateEnrollmentService certificates) {
        this.certificates = certificates;
    }

    @GetMapping("/certificate-enrollments")
    ListResponse<EnrollmentResponse> enrollments(RequestActor actor, @PathVariable UUID doctorId) {
        return ListResponse.of(certificates.listForAdmin(actor, doctorId));
    }

    @PostMapping("/certificate-reviews")
    EnrollmentResponse review(RequestActor actor, @PathVariable UUID doctorId, @RequestBody ReviewRequest body) {
        return certificates.review(actor, doctorId, body);
    }
}
