package com.inviz.visioncerti.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("dev")
public class DevAdminSessionController {
    public record Login(String username, String password) { }
    private final DevAdminSessionService sessions;

    public DevAdminSessionController(DevAdminSessionService sessions) { this.sessions = sessions; }

    @PostMapping("/v1/dev-admin/sessions")
    public ResponseEntity<DevAdminSessionService.Session> login(@RequestBody Login body,
            HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(sessions.login(body.username(), body.password(), request.getRemoteAddr()));
    }
}
