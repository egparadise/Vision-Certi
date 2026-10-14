package com.inviz.visioncerti.doctor;

import com.inviz.visioncerti.doctor.DoctorDtos.DevIdentityRequest;
import com.inviz.visioncerti.doctor.DoctorDtos.SessionResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEVELOPMENT ONLY and opt-in ({@code VISIONCERTI_DOCTOR_DEV_IDENTITY_ENABLED=true}). Accepts a claimed email and
 * subject as MOCK_IDENTITY under a non-Google issuer. It proves nothing and has no clinical validity.
 */
@RestController
@Profile("dev")
public class DevIdentityController {

    private final DoctorService doctors;

    public DevIdentityController(DoctorService doctors) {
        this.doctors = doctors;
    }

    @PostMapping("/v1/doctor-activation/dev-identity")
    ResponseEntity<SessionResponse> devIdentity(@RequestBody DevIdentityRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(doctors.completeDevIdentity(body == null ? null : body.challengeId(),
                        body == null ? null : body.email(), body == null ? null : body.subject()));
    }
}
