package com.inviz.visioncerti.certificate;

import com.inviz.visioncerti.common.Hashing;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.json.JsonMapper;

/**
 * TEST ONLY deterministic mobile adapter. A callback is accepted only with a valid HMAC-SHA256 of the raw body in
 * {@code x-fake-signature}; the body is then parsed. It is not a real vendor contract and is never on the classpath of
 * the application.
 */
final class FakeMobileProvider implements MobileIdentityProvider {

    static final String NAME = "fake-mobile-test";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final byte[] secret = "fake-provider-shared-secret-for-tests".getBytes(StandardCharsets.UTF_8);
    /** Transactions the "provider" was told about (it learns the nonce at start, like a real provider would). */
    final Map<java.util.UUID, Transaction> started = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public Map<String, Object> start(Transaction transaction) {
        started.put(transaction.transactionId(), transaction);
        return Map.of("appLink", "fake-mobile://approve/" + transaction.transactionId());
    }

    @Override
    @SuppressWarnings("unchecked")
    public VerifiedMobileAuthentication verifyCallback(Transaction transaction, Map<String, String> headers,
            byte[] body) {
        String presented = headers.get("x-fake-signature");
        if (presented == null || !Hashing.constantTimeEquals(presented, hmac(body))) {
            throw new IllegalArgumentException("unsigned or forged callback");
        }
        Map<String, Object> claims = JSON.readValue(body, Map.class);
        return new VerifiedMobileAuthentication(java.util.UUID.fromString((String) claims.get("transactionId")),
                (String) claims.get("purpose"), NAME, (String) claims.get("subject"), (String) claims.get("nonce"),
                Instant.parse((String) claims.get("authenticatedAt")));
    }

    /** What the provider would post for a completed authentication. */
    byte[] body(Transaction t, String subject, String nonce, Instant at) {
        return ("{\"transactionId\":\"" + t.transactionId() + "\",\"purpose\":\"" + t.purpose() + "\",\"nonce\":\""
                + nonce + "\",\"subject\":\"" + subject + "\",\"authenticatedAt\":\"" + at + "\",\"result\":\"SUCCESS\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    String hmac(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
