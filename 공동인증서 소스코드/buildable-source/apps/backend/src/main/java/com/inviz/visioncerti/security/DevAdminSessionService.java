package com.inviz.visioncerti.security;

import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.common.Hashing;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Local-network development admin login. Production staff authentication remains OIDC. */
@Service
@Profile("dev")
public class DevAdminSessionService {
    public record Session(String token, Instant expiresAt) { }
    private record Failure(int count, Instant lockedUntil) { }
    private final Map<String, Instant> sessions = new ConcurrentHashMap<>();
    private final Map<String, Failure> failures = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final String password;

    public DevAdminSessionService(Clock clock,
            @Value("${visioncerti.dev.admin-password:}") String password) {
        this.clock = clock;
        this.password = password;
    }

    public boolean configured() { return password != null && !password.isBlank(); }

    public Session login(String username, String submitted, String source) {
        if (!configured()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "DEV_ADMIN_LOGIN_DISABLED", "Local admin login is not configured", false);
        String key = source == null ? "unknown" : source;
        Instant now = clock.instant();
        Failure prior = failures.get(key);
        if (prior != null && prior.lockedUntil() != null && now.isBefore(prior.lockedUntil())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "DEV_ADMIN_LOGIN_LOCKED",
                    "Try again after the lockout expires", false);
        }
        if (!"scpark".equals(username) || !Hashing.constantTimeEquals(password, submitted)) {
            int count = prior == null || prior.lockedUntil() != null && !now.isBefore(prior.lockedUntil())
                    ? 1 : prior.count() + 1;
            failures.put(key, new Failure(count, count >= 10 ? now.plus(Duration.ofMinutes(10)) : null));
            throw ApiException.forbidden("DEV_ADMIN_LOGIN_FAILED", "Invalid administrator credentials");
        }
        failures.remove(key);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiry = now.plus(Duration.ofHours(8));
        sessions.put(Hashing.sha256Hex(token), expiry);
        return new Session(token, expiry);
    }

    public boolean accepts(String authorization) {
        if (!configured() || authorization == null || !authorization.startsWith("Bearer ")) return false;
        String token = authorization.substring(7);
        Instant expiry = sessions.get(Hashing.sha256Hex(token));
        return expiry != null && clock.instant().isBefore(expiry);
    }
}
