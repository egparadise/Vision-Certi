package com.inviz.visioncerti.certificate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Provider-neutral mobile certificate authentication. An adapter must authenticate every callback server-side
 * (provider signature, mTLS or transaction introspection) and return the stable provider subject; a callback body that
 * merely claims success must be rejected. No real adapter is bundled.
 */
public interface MobileIdentityProvider {

    String name();

    boolean configured();

    /** Starts the provider transaction and returns non-secret client data (e.g. app link); may call the provider. */
    Map<String, Object> start(Transaction transaction);

    /**
     * Verifies a provider callback for this transaction. Must throw unless the callback is authentic and refers to
     * exactly this transaction, purpose and nonce.
     */
    VerifiedMobileAuthentication verifyCallback(Transaction transaction, Map<String, String> headers, byte[] body);

    record Transaction(UUID transactionId, String purpose, String nonce, Instant createdAt, Instant expiresAt) {
    }

    record VerifiedMobileAuthentication(UUID transactionId, String purpose, String provider, String providerSubject,
            String nonce, Instant authenticatedAt) {
        @Override
        public String toString() {
            return "VerifiedMobileAuthentication[" + transactionId + ", " + provider + ", <redacted>]";
        }
    }

    /** Default: nothing configured; every mobile route answers 503 MOBILE_PROVIDER_NOT_CONFIGURED. */
    final class NotConfigured implements MobileIdentityProvider {

        private final String requested;

        public NotConfigured(String requested) {
            this.requested = requested == null || requested.isBlank() ? null : requested.trim();
        }

        @Override
        public String name() {
            return requested;
        }

        @Override
        public boolean configured() {
            return false;
        }

        @Override
        public Map<String, Object> start(Transaction transaction) {
            throw new IllegalStateException("not configured");
        }

        @Override
        public VerifiedMobileAuthentication verifyCallback(Transaction transaction, Map<String, String> headers,
                byte[] body) {
            throw new IllegalStateException("not configured");
        }
    }
}
