package com.inviz.visioncerti.security;

import com.inviz.visioncerti.common.CorrelationIdFilter;
import com.inviz.visioncerti.integration.IntegrationAuthFilter;
import com.inviz.visioncerti.security.StaffDirectory.StaffPrincipal;
import com.inviz.visioncerti.security.StaffTokenVerifier.StaffTokenException;
import com.inviz.visioncerti.security.StaffTokenVerifier.VerifiedStaffToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Staff authentication when {@code visioncerti.staff-auth.mode=OIDC} (always in prod).
 *
 * <ul>
 *   <li>Any request carrying an identity override header ({@code X-Tenant-Id}, {@code X-Actor-Id},
 *       {@code X-Actor-Role}) is rejected with 400 {@code HEADER_OVERRIDE_REJECTED}.</li>
 *   <li>Public routes (health, runtime capabilities), physician routes (own bearer, see
 *       {@link DevHeaderAuthFilter#isPhysicianRoute}), personal-signup routes and HMAC integration routes are left to
 *       their own authentication.</li>
 *   <li>Every other {@code /v1/} route requires {@code Authorization: Bearer <JWT>} verified by
 *       {@link StaffTokenVerifier} and an ACTIVE (issuer, subject) mapping in {@link StaffDirectory}; the mapping alone
 *       decides tenant and role.</li>
 * </ul>
 */
public class StaffAuthFilter extends OncePerRequestFilter implements Ordered {

    public static final String PRINCIPAL_ATTRIBUTE = StaffAuthFilter.class.getName() + ".principal";
    static final List<String> OVERRIDE_HEADERS = List.of("X-Tenant-Id", "X-Actor-Id", "X-Actor-Role");
    private static final Pattern JWT = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    private final StaffTokenVerifier verifier;
    private final StaffDirectory directory;

    public StaffAuthFilter(StaffTokenVerifier verifier, StaffDirectory directory) {
        this.verifier = verifier;
        this.directory = directory;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !path(request).startsWith("/v1/");
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    static boolean isPublicRoute(HttpServletRequest request, String path) {
        return ("GET".equals(request.getMethod())
                        && (path.equals("/v1/health") || path.equals("/v1/runtime/capabilities")))
                || DevHeaderAuthFilter.isPublicEnrollmentRoute(request, path)
                || DevHeaderAuthFilter.isPhysicianRoute(request, path)
                || path.startsWith(IntegrationAuthFilter.PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        for (String header : OVERRIDE_HEADERS) {
            if (request.getHeader(header) != null) {
                reject(request, response, HttpServletResponse.SC_BAD_REQUEST, "HEADER_OVERRIDE_REJECTED",
                        "Identity override headers are not accepted; authenticate with a bearer token");
                return;
            }
        }
        String path = path(request);
        if (isPublicRoute(request, path)) {
            chain.doFilter(request, response);
            return;
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()).trim() : null;
        if (token == null || !JWT.matcher(token).matches()) {
            reject(request, response, HttpServletResponse.SC_UNAUTHORIZED, "STAFF_TOKEN_REQUIRED",
                    "Staff routes require an OIDC bearer token");
            return;
        }
        VerifiedStaffToken verified;
        try {
            verified = verifier.verify(token);
        } catch (StaffTokenException e) {
            if (e.unavailable()) {
                reject(request, response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "STAFF_AUTH_UNAVAILABLE",
                        "The identity provider keys are unavailable; retry later");
            } else {
                reject(request, response, HttpServletResponse.SC_UNAUTHORIZED, "STAFF_TOKEN_INVALID",
                        "The staff token is invalid or expired");
            }
            return;
        }
        StaffPrincipal principal = directory.resolve(verified.issuer(), verified.subject()).orElse(null);
        if (principal == null) {
            reject(request, response, HttpServletResponse.SC_FORBIDDEN, "STAFF_NOT_AUTHORIZED",
                    "This identity is not mapped to an active staff role");
            return;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        RequestActorResolver.bind(request, new RequestActor(principal.tenantId(), principal.actorId(),
                principal.role()));
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletRequest request, HttpServletResponse response, int status, String code,
            String message) throws IOException {
        // Messages are fixed constants without quotes, so manual JSON is safe here.
        String body = "{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"correlationId\":\""
                + CorrelationIdFilter.current(request) + "\",\"retryable\":" + (status == 503) + "}";
        response.setStatus(status);
        if (status == HttpServletResponse.SC_UNAUTHORIZED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }
}
