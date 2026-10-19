package com.inviz.visioncerti.doctor;

import com.inviz.visioncerti.doctor.DoctorDtos.ChallengeResponse;
import com.inviz.visioncerti.doctor.DoctorDtos.ConfigResponse;
import com.inviz.visioncerti.doctor.DoctorDtos.ConsentRequest;
import com.inviz.visioncerti.doctor.DoctorDtos.CreateActivationChallenge;
import com.inviz.visioncerti.doctor.DoctorDtos.DoctorResponse;
import com.inviz.visioncerti.doctor.DoctorDtos.GoogleIdentityRequest;
import com.inviz.visioncerti.doctor.DoctorDtos.MeResponse;
import com.inviz.visioncerti.doctor.DoctorDtos.SessionResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Physician-facing activation and login. Public routes need no identity; consent, activate and me require the
 * physician's own bearer session. Responses carrying nonces or tokens are never cached.
 */
@RestController
@RequestMapping("/v1/doctor-activation")
public class DoctorActivationController {

    private final DoctorService doctors;

    public DoctorActivationController(DoctorService doctors) {
        this.doctors = doctors;
    }

    @GetMapping("/config")
    ConfigResponse config() {
        return doctors.config();
    }

    @PostMapping("/challenges")
    ResponseEntity<ChallengeResponse> challenge(@RequestBody CreateActivationChallenge body,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(doctors.createActivationChallenge(body == null ? null : body.invitationCode(),
                        request.getRemoteAddr()));
    }

    @PostMapping("/login-challenges")
    ResponseEntity<ChallengeResponse> loginChallenge(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(doctors.createLoginChallenge(request.getRemoteAddr()));
    }

    @PostMapping("/google")
    ResponseEntity<SessionResponse> google(@RequestBody GoogleIdentityRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(doctors.completeGoogle(body == null ? null : body.challengeId(),
                        body == null ? null : body.idToken()));
    }

    @PostMapping("/consent")
    DoctorResponse consent(DoctorPrincipal principal, @RequestBody ConsentRequest body) {
        return doctors.consent(principal, body);
    }

    @PostMapping("/activate")
    DoctorResponse activate(DoctorPrincipal principal) {
        return doctors.activate(principal);
    }

    @GetMapping("/me")
    ResponseEntity<MeResponse> me(DoctorPrincipal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(doctors.me(principal));
    }
}
