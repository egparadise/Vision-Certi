package com.inviz.visioncerti.doctor;

import com.inviz.visioncerti.certificate.CertificateDtos.ChallengeResponse;
import com.inviz.visioncerti.certificate.CertificateEnrollmentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/physician-accounts")
public class PhysicianAccountController {
    private final PhysicianAccountService accounts;
    private final CertificateEnrollmentService certificates;
    public PhysicianAccountController(PhysicianAccountService accounts, CertificateEnrollmentService certificates) {
        this.accounts = accounts; this.certificates = certificates;
    }
    @GetMapping("/config")
    public PhysicianAccountService.Config config() { return accounts.config(); }
    @PostMapping("/registrations")
    public ResponseEntity<PhysicianAccountService.Receipt> register(@RequestBody PhysicianAccountService.Register body,
            HttpServletRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(accounts.register(body, request.getRemoteAddr()));
    }
    @PostMapping("/sessions")
    public ResponseEntity<PhysicianAccountService.Receipt> login(@RequestBody PhysicianAccountService.Login body,
            HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.login(body, request.getRemoteAddr()));
    }
    @GetMapping("/me")
    public ResponseEntity<PhysicianAccountService.View> me(@RequestHeader(value="Authorization", required=false) String auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.me(auth));
    }
    @PostMapping("/certificate-challenges")
    public ResponseEntity<ChallengeResponse> challenge(@RequestHeader(value="Authorization", required=false) String auth,
            HttpServletRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(certificates.createPacsEnrollmentChallenge(accounts.doctor(auth), request.getRemoteAddr()));
    }
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value="Authorization", required=false) String auth) {
        accounts.logout(auth); return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
