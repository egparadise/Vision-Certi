package com.inviz.visioncerti.certificate;

import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Server-side trust material: CA certificates used as PKIX trust anchors and CRLs used for revocation. Never taken
 * from the client. Implementations are re-read on each call so refreshed CRL files take effect.
 */
public interface CertificateTrustSource {

    List<X509Certificate> trustAnchors();

    List<X509CRL> crls();

    /** True when this source's anchor is the dev MOCK_CERTIFICATE CA. */
    default boolean isMockAnchor(X509Certificate anchor) {
        return false;
    }

    static CertificateTrustSource composite(List<CertificateTrustSource> sources) {
        return new CertificateTrustSource() {
            @Override
            public List<X509Certificate> trustAnchors() {
                List<X509Certificate> all = new ArrayList<>();
                sources.forEach(s -> all.addAll(s.trustAnchors()));
                return all;
            }

            @Override
            public List<X509CRL> crls() {
                List<X509CRL> all = new ArrayList<>();
                sources.forEach(s -> all.addAll(s.crls()));
                return all;
            }

            @Override
            public boolean isMockAnchor(X509Certificate anchor) {
                return sources.stream().anyMatch(s -> s.isMockAnchor(anchor));
            }
        };
    }
}
