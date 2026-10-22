package com.inviz.visioncerti.integration;

import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.common.CorrelationIdFilter;
import com.inviz.visioncerti.common.Hashing;
import com.inviz.visioncerti.store.Kind;
import com.inviz.visioncerti.store.Store;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * HMAC service authentication for {@code /v1/integrations/pacs/**}. Nothing else (dev headers, doctor bearers) is
 * accepted on these routes. The canonical string covers audience, client, method, raw path and query, timestamp,
 * nonce and the SHA-256 of the exact body; nonces are single use per client within the replay window.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class IntegrationAuthFilter extends OncePerRequestFilter {

    public static final String PREFIX = "/v1/integrations/pacs/";
    public static final String CLIENT_ATTRIBUTE = IntegrationAuthFilter.class.getName() + ".client";
    static final String H_CLIENT = "X-VC-Client-Id";
    static final String H_TIMESTAMP = "X-VC-Timestamp";
    static final String H_NONCE = "X-VC-Nonce";
    static final String H_CONTENT_SHA256 = "X-VC-Content-SHA256";
    static final String H_SIGNATURE = "X-VC-Signature";
    static final int MAX_BODY_BYTES = 512 * 1024;
    static final Duration MAX_SKEW = Duration.ofMinutes(5);
    static final Duration REPLAY_WINDOW = Duration.ofMinutes(10);
    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{22,64}");
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SAFE_PATH = Pattern.compile("/v1/integrations/pacs/[A-Za-z0-9/_.-]+");

    /** A used request nonce; only the SHA-256 of client + nonce is stored, for the replay window. */
    record SeenNonce(UUID id, String clientId, String nonceKey, Instant seenAt, Instant expiresAt) {
    }

    static final Kind<SeenNonce> NONCES = Kind.of("integration_nonces", SeenNonce.class)
            .id(SeenNonce::id).expiresAt(SeenNonce::expiresAt)
            .unique("nonce_key", SeenNonce::nonceKey,
                    () -> ApiException.conflict("INTEGRATION_NONCE_REPLAYED", "This request nonce was already used"))
            .build();

    private final IntegrationClients clients;
    private final Clock clock;
    private final Store store;
    private final AuditService audit;
    private volatile Instant lastPrune = Instant.EPOCH;

    public IntegrationAuthFilter(IntegrationClients clients, Clock clock, Store store, AuditService audit) {
        this.clients = clients;
        this.clock = clock;
        this.store = store;
        this.audit = audit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !path(request).startsWith(PREFIX);
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    /** {@code VC-HMAC-SHA256-V1} canonical string (also implemented by the PACS client). */
    public static String canonical(String audience, String clientId, String method, String rawPath, String rawQuery,
            String timestamp, String nonce, String contentSha256) {
        return String.join("\n", "VC-HMAC-SHA256-V1", audience, clientId, method, rawPath,
                rawQuery == null ? "" : rawQuery, timestamp, nonce, contentSha256);
    }

    public static String sign(byte[] secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            if (!clients.configured()) {
                throw IntegrationClients.notConfigured();
            }
            String path = path(request);
            if (!SAFE_PATH.matcher(path).matches() || path.contains("..") || path.contains("//")) {
                throw authInvalid();
            }
            String clientId = request.getHeader(H_CLIENT);
            IntegrationClient client = clients.find(clientId).orElseThrow(IntegrationAuthFilter::authInvalid);
            String timestamp = request.getHeader(H_TIMESTAMP);
            String nonce = request.getHeader(H_NONCE);
            String contentSha = request.getHeader(H_CONTENT_SHA256);
            String signature = request.getHeader(H_SIGNATURE);
            if (timestamp == null || nonce == null || contentSha == null || signature == null
                    || !NONCE.matcher(nonce).matches() || !HEX64.matcher(contentSha).matches()) {
                throw authInvalid();
            }
            Instant now = clock.instant();
            long seconds;
            try {
                seconds = Long.parseLong(timestamp);
            } catch (NumberFormatException e) {
                throw authInvalid();
            }
            if (Math.abs(now.getEpochSecond() - seconds) > MAX_SKEW.getSeconds()) {
                throw authInvalid();
            }
            byte[] body = readBody(request);
            if (!Hashing.constantTimeEquals(Hashing.sha256Hex(body), contentSha)) {
                throw authInvalid();
            }
            String expected = sign(client.secret(), canonical(client.audience(), client.clientId(),
                    request.getMethod(), path, request.getQueryString(), timestamp, nonce, contentSha));
            if (!Hashing.constantTimeEquals(expected, signature)) {
                throw authInvalid();
            }
            rememberNonce(client.clientId(), nonce, now);
            request.setAttribute(CLIENT_ATTRIBUTE, client);
            chain.doFilter(new CachedBodyRequest(request, body), response);
        } catch (ApiException e) {
            if (path(request).startsWith(PREFIX + "certificate-proofs/") && clients.configured()
                    && clients.require().tenantIds().size() == 1) {
                IntegrationClient configured = clients.require();
                var details = PacsRequestContext.from(request).details();
                details.put("clientId", configured.clientId());
                details.put("stage", "INTEGRATION_AUTH");
                details.put("code", e.code());
                audit.record(configured.tenantIds().iterator().next(), "integration:unverified",
                        "PACS_CERTIFICATE_PROOF_DENIED", "PACS_CERTIFICATE_PROOF", "integration-auth",
                        AuditService.DENIED, details);
            }
            reject(request, response, e);
        }
    }

    /**
     * Records the nonce durably (UNIQUE per client + nonce, shared by all instances and kept across restarts for the
     * replay window); a second use anywhere is refused with 409 {@code INTEGRATION_NONCE_REPLAYED}.
     */
    private void rememberNonce(String clientId, String nonce, Instant now) {
        String key = Hashing.sha256Hex(clientId + "\u0000" + nonce);
        boolean prune = now.isAfter(lastPrune.plusSeconds(60));
        store.run(tx -> {
            if (prune) {
                tx.deleteExpired(NONCES, now);
            }
            tx.insert(NONCES, new SeenNonce(UUID.randomUUID(), clientId, key, now, now.plus(REPLAY_WINDOW)));
        });
        if (prune) {
            lastPrune = now;
        }
    }

    private static byte[] readBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "INTEGRATION_BODY_TOO_LARGE", "Body too large");
        }
        try (InputStream in = request.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_BODY_BYTES) {
                    throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "INTEGRATION_BODY_TOO_LARGE",
                            "Body too large");
                }
            }
            return out.toByteArray();
        }
    }

    static ApiException authInvalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INTEGRATION_AUTH_INVALID",
                "Integration request authentication failed");
    }

    private static void reject(HttpServletRequest request, HttpServletResponse response, ApiException e)
            throws IOException {
        // Codes and messages are fixed constants without quotes, so manual JSON is safe here.
        String body = "{\"code\":\"" + e.code() + "\",\"message\":\"" + e.getMessage().replace("\"", "'")
                + "\",\"correlationId\":\"" + CorrelationIdFilter.current(request) + "\",\"retryable\":"
                + e.retryable() + "}";
        response.setStatus(e.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }

    /** Replays the already-read body to the controllers. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
