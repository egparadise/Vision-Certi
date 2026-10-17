package com.inviz.visioncerti.doctor;

import com.inviz.visioncerti.common.ListResponse;
import com.inviz.visioncerti.signing.SignRequestService;
import com.inviz.visioncerti.signing.SigningDtos.ApproveSignRequest;
import com.inviz.visioncerti.signing.SigningDtos.SignRequestResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Registered physicians see and approve only their own sign requests, through their bearer session. */
@RestController
@RequestMapping("/v1/doctor/sign-requests")
public class DoctorSigningController {

    private final DoctorService doctors;
    private final SignRequestService signRequests;

    public DoctorSigningController(DoctorService doctors, SignRequestService signRequests) {
        this.doctors = doctors;
        this.signRequests = signRequests;
    }

    @GetMapping
    ResponseEntity<ListResponse<SignRequestResponse>> list(DoctorPrincipal principal) {
        doctors.signerFor(principal).assertCanSign();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ListResponse.of(
                signRequests.listForDoctor(principal.tenantId(), principal.doctorId().toString())));
    }

    @GetMapping("/{id}")
    ResponseEntity<SignRequestResponse> get(DoctorPrincipal principal, @PathVariable UUID id) {
        doctors.signerFor(principal).assertCanSign();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(signRequests.getForDoctor(principal.tenantId(), principal.doctorId().toString(), id));
    }

    /** The exact bound PDF bytes, so the physician can read what they are about to sign. */
    @GetMapping("/{id}/document")
    ResponseEntity<byte[]> document(DoctorPrincipal principal, @PathVariable UUID id) {
        doctors.signerFor(principal).assertCanSign();
        SignRequestService.BoundDocument document = signRequests.documentForDoctor(principal.tenantId(),
                principal.doctorId().toString(), id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.contentType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Document-Sha256", document.sha256())
                .header("X-Document-Version-Id", document.documentVersionId().toString())
                .body(document.content());
    }

    @PostMapping("/{id}/approve")
    ResponseEntity<SignRequestResponse> approve(DoctorPrincipal principal, @PathVariable UUID id,
            @Valid @RequestBody ApproveSignRequest body) {
        return ResponseEntity.accepted()
                .body(signRequests.approveAsRegisteredDoctor(doctors.signerFor(principal), id, body));
    }
}
