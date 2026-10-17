package com.inviz.visioncerti.integration;

import com.inviz.visioncerti.common.IdempotencyService;
import com.inviz.visioncerti.doctor.DoctorPrincipal;
import com.inviz.visioncerti.integration.PacsIntegrationDtos.HandoffRequest;
import com.inviz.visioncerti.integration.PacsIntegrationDtos.HandoffResponse;
import com.inviz.visioncerti.integration.PacsIntegrationDtos.RedeemRequest;
import com.inviz.visioncerti.integration.PacsIntegrationDtos.ReportSignRequestCreate;
import com.inviz.visioncerti.integration.PacsIntegrationDtos.ReportSignRequestCreated;
import com.inviz.visioncerti.integration.PacsIntegrationDtos.ReportSignRequestStatus;
import com.inviz.visioncerti.integration.PacsIntegrationDtos.VerifiedIdentityResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * PACS integration. {@code /v1/doctor/pacs-handoffs} needs the physician's own bearer session (certificate login);
 * {@code /v1/integrations/pacs/**} needs HMAC service authentication ({@link IntegrationAuthFilter}).
 */
@RestController
public class PacsIntegrationController {

    private final PacsHandoffService handoffs;
    private final PacsReportSignService reports;
    private final PacsEnrollmentService enrollments;

    public PacsIntegrationController(PacsHandoffService handoffs, PacsReportSignService reports,
            PacsEnrollmentService enrollments) {
        this.handoffs = handoffs;
        this.reports = reports;
        this.enrollments = enrollments;
    }

    @PostMapping("/v1/integrations/pacs/enrollments/start")
    ResponseEntity<PacsIntegrationDtos.EnrollmentStartResponse> startEnrollment(HttpServletRequest request,
            @RequestBody(required = false) PacsIntegrationDtos.EnrollmentStartRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(enrollments.start(client(request), body));
    }

    @PostMapping("/v1/integrations/pacs/enrollments/status")
    ResponseEntity<PacsIntegrationDtos.EnrollmentStatusResponse> enrollmentStatus(HttpServletRequest request,
            @RequestBody(required = false) PacsIntegrationDtos.EnrollmentStatusRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(enrollments.status(client(request), body));
    }

    @PostMapping("/v1/doctor/pacs-handoffs")
    ResponseEntity<HandoffResponse> issue(DoctorPrincipal principal, @RequestBody(required = false) HandoffRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(handoffs.issue(principal, body == null ? null : body.audience()));
    }

    @PostMapping("/v1/integrations/pacs/handoffs/redeem")
    ResponseEntity<VerifiedIdentityResponse> redeem(HttpServletRequest request,
            @RequestBody(required = false) RedeemRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(handoffs.redeem(client(request), body == null ? null : body.handoffCode()));
    }

    @PostMapping("/v1/integrations/pacs/certificate-logins/confirm")
    ResponseEntity<PacsIntegrationDtos.CertificateLoginConfirmationResponse> confirmCertificateLogin(
            HttpServletRequest request,
            @RequestBody(required = false) PacsIntegrationDtos.CertificateLoginConfirmationRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(handoffs.confirmLogin(client(request), body));
    }

    @PostMapping("/v1/integrations/pacs/report-sign-requests")
    ResponseEntity<ReportSignRequestCreated> create(HttpServletRequest request,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) ReportSignRequestCreate body) {
        return reports.create(client(request), idempotencyKey, body);
    }

    @GetMapping("/v1/integrations/pacs/report-sign-requests/{id}")
    ResponseEntity<ReportSignRequestStatus> get(HttpServletRequest request, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reports.get(client(request), id));
    }

    private static IntegrationClient client(HttpServletRequest request) {
        Object client = request.getAttribute(IntegrationAuthFilter.CLIENT_ATTRIBUTE);
        if (!(client instanceof IntegrationClient c)) {
            throw IntegrationAuthFilter.authInvalid(); // the filter did not authenticate this request
        }
        return c;
    }
}
