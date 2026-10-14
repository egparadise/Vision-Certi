package com.inviz.visioncerti.enrollment;

import com.inviz.visioncerti.enrollment.EnrollmentDtos.CreatePhoneChallenge;
import com.inviz.visioncerti.enrollment.EnrollmentDtos.PhoneChallengeResponse;
import com.inviz.visioncerti.enrollment.EnrollmentDtos.PhoneVerificationResponse;
import com.inviz.visioncerti.enrollment.EnrollmentDtos.RegistrationRequest;
import com.inviz.visioncerti.enrollment.EnrollmentDtos.RegistrationResponse;
import com.inviz.visioncerti.enrollment.EnrollmentDtos.VerifyPhoneChallenge;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public signup routes. These exact POST routes are the only ones the dev header filter lets through without
 * an identity (see {@code DevHeaderAuthFilter#isPublicEnrollmentRoute}). Responses carry codes or tokens, so
 * they are never cached.
 */
@RestController
@RequestMapping(path = "/v1/enrollment", consumes = MediaType.APPLICATION_JSON_VALUE)
public class EnrollmentController {

    private final EnrollmentService enrollment;

    public EnrollmentController(EnrollmentService enrollment) {
        this.enrollment = enrollment;
    }

    @PostMapping("/phone-challenges")
    ResponseEntity<PhoneChallengeResponse> createChallenge(@RequestBody CreatePhoneChallenge body,
            HttpServletRequest request) {
        // Remote address only: forwarding headers are client-controlled until a trusted proxy is configured.
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(enrollment.createChallenge(body, request.getRemoteAddr()));
    }

    @PostMapping("/phone-challenges/{challengeId}/verify")
    ResponseEntity<PhoneVerificationResponse> verify(@PathVariable String challengeId,
            @RequestBody VerifyPhoneChallenge body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(enrollment.verify(challengeId, body));
    }

    @PostMapping("/registrations")
    ResponseEntity<RegistrationResponse> register(@RequestBody RegistrationRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(enrollment.register(body));
    }
}
