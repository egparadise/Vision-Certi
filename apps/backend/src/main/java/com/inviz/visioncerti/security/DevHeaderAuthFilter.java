package com.inviz.visioncerti.security;

import com.inviz.visioncerti.common.CorrelationIdFilter;
import com.inviz.visioncerti.integration.IntegrationAuthFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * DEVELOPMENT ONLY. Trusts {@code X-Tenant-Id} and {@code X-Actor-Id} headers to identify the caller.
 * This is not authentication: any client can claim any synthetic identity. The bean exists only under
 * the {@code dev} profile and {@link FailClosedProfileGuard} refuses to start any other profile, so
 * these headers never act as credentials elsewhere. With {@code visioncerti.staff-auth.mode=OIDC} the filter does not
 * exist even in {@code dev} ({@link StaffAuthFilter} authenticates staff instead).
 */
@Component
@Profile("dev")
@Conditional(SyntheticActorsCondition.class)
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DevHeaderAuthFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String ACTOR_HEADER = "X-Actor-Id";
    private static final Pattern PUBLIC_VERIFY_ROUTE = Pattern.compile(
            "/v1/enrollment/phone-challenges/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/verify");
    private static final String UUID_SEGMENT = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern DOCTOR_GET_ROUTE = Pattern.compile(
            "/v1/doctor-activation/(config|me)|/v1/doctor/sign-requests(/" + UUID_SEGMENT + "(/document|/certificate-payload)?)?"
                    + "|/v1/certificate-enrollment/(config|enrollments)"
                    + "|/v1/physician-accounts/(config|me)");
    private static final Pattern DOCTOR_POST_ROUTE = Pattern.compile(
            "/v1/doctor-activation/(challenges|login-challenges|google|dev-identity|consent|activate)"
                    + "|/v1/doctor/sign-requests/" + UUID_SEGMENT + "/approve"
                    + "|/v1/doctor/pacs-handoffs"
                    + "|/v1/certificate-enrollment/(challenges|verify|login-challenges|login|mobile/transactions"
                    + "|dev/mock-sign)"
                    + "|/v1/certificate-enrollment/mobile/transactions/" + UUID_SEGMENT + "/(callback|consume)"
                    + "|/v1/certificate-enrollment/enrollments/" + UUID_SEGMENT + "/(consent|unregister)"
                    + "|/v1/physician-accounts/(registrations|sessions|certificate-challenges|logout)");
    private static final Pattern DOCTOR_PUT_ROUTE = Pattern.compile(
            "/v1/certificate-enrollment/enrollments/" + UUID_SEGMENT + "/medical-credentials");

    private final ActorDirectory directory;
    private final DevAdminSessionService adminSessions;

    public DevHeaderAuthFilter(ActorDirectory directory, DevAdminSessionService adminSessions) {
        this.directory = directory;
        this.adminSessions = adminSessions;
    }

    /** Integration routes ({@link IntegrationAuthFilter#PREFIX}) are authenticated only by HMAC, never by dev headers. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/v1/") || path.equals("/v1/health") || path.equals("/v1/runtime/capabilities")
                || (path.equals("/v1/dev-admin/sessions") && "POST".equals(request.getMethod()))
                || isPublicEnrollmentRoute(request, path)
                || isPacsCertificateProofPublicRoute(request, path)
                || isPhysicianRoute(request, path) || path.startsWith(IntegrationAuthFilter.PREFIX);
    }

    /**
     * Exact physician routes. They never accept dev identities: they are either public (config, challenges,
     * identity verification) or require the physician's own bearer session, checked by {@code DoctorPrincipalResolver}.
     */
    static boolean isPhysicianRoute(HttpServletRequest request, String path) {
        String method = request.getMethod();
        return ("GET".equals(method) && DOCTOR_GET_ROUTE.matcher(path).matches())
                || ("POST".equals(method) && DOCTOR_POST_ROUTE.matcher(path).matches())
                || ("PUT".equals(method) && DOCTOR_PUT_ROUTE.matcher(path).matches());
    }

    /**
     * Exact signup routes that must work before an identity exists. Matching uses the raw URI, so trailing
     * slashes, path parameters, encoded characters and other methods all fall back to the filter.
     */
    static boolean isPublicEnrollmentRoute(HttpServletRequest request, String path) {
        return "POST".equals(request.getMethod())
                && (path.equals("/v1/enrollment/phone-challenges")
                        || path.equals("/v1/enrollment/registrations")
                        || PUBLIC_VERIFY_ROUTE.matcher(path).matches());
    }

    /** Exact public PC proof routes; redeem/status/events require S256 possession. Integration routes require HMAC. */
    static boolean isPacsCertificateProofPublicRoute(HttpServletRequest request, String path) {
        if ("GET".equals(request.getMethod()) && path.equals("/v1/pacs-certificate-proofs/pc/capabilities")) return true;
        return "POST".equals(request.getMethod())
                && (path.equals("/v1/pacs-certificate-proofs/challenges")
                        || path.equals("/v1/pacs-certificate-proofs/verify")
                        || path.equals("/v1/pacs-certificate-proofs/pc/redeem")
                        || path.equals("/v1/pacs-certificate-proofs/pc/status")
                        || path.equals("/v1/pacs-certificate-proofs/pc/events")
                        || path.equals("/v1/pacs-certificate-proofs/pc/account/activate")
                        || path.equals("/v1/pacs-certificate-proofs/pc/account/status")
                        || path.equals("/v1/pacs-certificate-proofs/pc/account/heartbeat")
                        || path.equals("/v1/pacs-certificate-proofs/pc/account/events"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String tenantId = request.getHeader(TENANT_HEADER);
        String actorId = request.getHeader(ACTOR_HEADER);
        if (tenantId == null || tenantId.isBlank() || actorId == null || actorId.isBlank()) {
            reject(request, response, "DEV_IDENTITY_REQUIRED",
                    "X-Tenant-Id and X-Actor-Id headers are required in the dev profile");
            return;
        }
        Optional<ActorDirectory.Member> member = directory.member(tenantId, actorId);
        if (member.isEmpty()) {
            reject(request, response, "DEV_IDENTITY_UNKNOWN",
                    "Unknown synthetic tenant or actor for the dev profile");
            return;
        }
        if (member.get().role() == ActorRole.ADMIN && adminSessions.configured()
                && !adminSessions.accepts(request.getHeader("Authorization"))) {
            reject(request, response, "DEV_ADMIN_SESSION_REQUIRED", "Administrator login is required");
            return;
        }
        RequestActorResolver.bind(request, new RequestActor(tenantId, actorId, member.get().role()));
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletRequest request, HttpServletResponse response, String code,
            String message) throws IOException {
        // Messages are fixed constants without quotes, so manual JSON is safe here.
        String body = "{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"correlationId\":\""
                + CorrelationIdFilter.current(request) + "\",\"retryable\":false}";
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }
}
