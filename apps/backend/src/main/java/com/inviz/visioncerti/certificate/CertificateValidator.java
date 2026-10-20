package com.inviz.visioncerti.certificate;

import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.common.Hashing;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertStore;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXCertPathBuilderResult;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.security.cert.X509CRLEntry;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERIA5String;
import org.bouncycastle.asn1.x509.CertificatePolicies;
import org.bouncycastle.asn1.x509.PolicyInformation;
import org.bouncycastle.asn1.x509.PolicyQualifierId;
import org.bouncycastle.asn1.x509.PolicyQualifierInfo;
import org.bouncycastle.asn1.x509.UserNotice;
import org.springframework.http.HttpStatus;

/**
 * Validates a client certificate proof without trusting anything the client asserts about trust:
 * <ul>
 * <li>leaf policy: end-entity, keyUsage digitalSignature, RSA ≥ 2048 or EC P-256;</li>
 * <li>signature over the server-stored bytes with an allowed algorithm matching the key;</li>
 * <li>PKIX path (JDK CertPathBuilder) from the leaf through client-supplied intermediates to a configured anchor;</li>
 * <li>trust anchors are used only while valid, CA (basicConstraints), keyCertSign (if keyUsage present), and their
 * pathLenConstraint is enforced; the JDK builder does not check any of this for anchors;</li>
 * <li>revocation of every path element against configured CRLs: CRL signature by the issuer (cRLSign), issuer name,
 * {@code thisUpdate ≤ now < nextUpdate}. Missing → UNKNOWN, expired → STALE, listed → REVOKED.</li>
 * <li>CRL scope: only complete, direct, unpartitioned base CRLs are understood. Any authentic CRL for the issuer
 * that is a delta CRL, carries an issuingDistributionPoint (partition, reason subset, indirect), advertises deltas
 * (freshestCRL), has an unknown critical extension, or has a revoked entry with a critical extension or
 * certificateIssuer makes the status UNKNOWN (fail closed); it is never skipped in favour of an older full CRL.</li>
 * </ul>
 * No network access is made from certificate contents (no CRL distribution point, AIA or OCSP fetch).
 */
public class CertificateValidator {

    public static final String SHA256_WITH_RSA = "SHA256withRSA";
    public static final String SHA256_WITH_ECDSA = "SHA256withECDSA";
    public static final List<String> ALLOWED_ALGORITHMS = List.of(SHA256_WITH_RSA, SHA256_WITH_ECDSA);
    private static final int MAX_CERT_BYTES = 16 * 1024;
    private static final int MAX_CHAIN = 5;
    private static final int MAX_SIGNATURE_BYTES = 1024;

    public enum RevocationStatus { GOOD, REVOKED, UNKNOWN, STALE }

    /** Result of path + revocation validation. */
    public record Validation(X509Certificate leaf, List<X509Certificate> path, X509Certificate anchor,
            RevocationStatus revocation, Instant checkedAt, boolean mockAnchor) {
    }

    private final CertificateTrustSource trust;
    private final Clock clock;

    public CertificateValidator(CertificateTrustSource trust, Clock clock) {
        this.trust = trust;
        this.clock = clock;
    }

    public CertificateTrustSource trust() {
        return trust;
    }

    public boolean trustConfigured() {
        return !trust.trustAnchors().isEmpty();
    }

    // ------------------------------------------------------------------ parsing and leaf policy

    public X509Certificate parse(String base64) {
        byte[] der = decode(base64, MAX_CERT_BYTES, "certificate");
        return parseDer(der);
    }

    /** Parses exactly one DER certificate; trailing bytes (second certificate, key material, padding) are rejected. */
    public static X509Certificate parseDer(byte[] der) {
        X509Certificate certificate;
        try {
            certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
            if (!Arrays.equals(certificate.getEncoded(), der)) {
                throw invalid("인증서 값에 DER 인증서 1개 외의 데이터가 포함되어 있습니다.");
            }
        } catch (GeneralSecurityException | ClassCastException e) {
            throw invalid("인증서를 해석할 수 없습니다 (DER X.509 형식이어야 합니다).");
        }
        return certificate;
    }

    public List<X509Certificate> parseChain(List<String> chain) {
        if (chain == null) {
            return List.of();
        }
        if (chain.size() > MAX_CHAIN) {
            throw invalid("인증서 체인은 최대 " + MAX_CHAIN + "개까지 보낼 수 있습니다.");
        }
        List<X509Certificate> parsed = new ArrayList<>();
        for (String element : chain) {
            parsed.add(parse(element));
        }
        return parsed;
    }

    public void checkLeafPolicy(X509Certificate leaf) {
        if (leaf.getBasicConstraints() >= 0) {
            throw invalid("CA 인증서는 사용자 인증서로 등록할 수 없습니다.");
        }
        boolean[] keyUsage = leaf.getKeyUsage();
        if (keyUsage == null || keyUsage.length == 0 || !keyUsage[0]) {
            throw invalid("인증서의 키 용도(keyUsage)에 digitalSignature가 없습니다.");
        }
        keyAlgorithm(leaf.getPublicKey());
    }

    /** RSA (≥ 2048 bits) or EC on P-256; anything else is rejected. */
    public static String keyAlgorithm(PublicKey key) {
        if (key instanceof RSAPublicKey rsa) {
            if (rsa.getModulus().bitLength() < 2048) {
                throw invalid("RSA 키는 2048비트 이상이어야 합니다.");
            }
            return "RSA-" + rsa.getModulus().bitLength();
        }
        if (key instanceof ECPublicKey ec) {
            if (ec.getParams().getCurve().getField().getFieldSize() != 256
                    || !ec.getParams().getCurve().equals(p256().getCurve())) {
                throw invalid("EC 키는 P-256 곡선이어야 합니다.");
            }
            return "EC-P256";
        }
        throw invalid("지원하지 않는 공개키 알고리즘입니다 (RSA 또는 EC P-256).");
    }

    private static java.security.spec.ECParameterSpec p256() {
        try {
            java.security.AlgorithmParameters parameters = java.security.AlgorithmParameters.getInstance("EC");
            parameters.init(new java.security.spec.ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(java.security.spec.ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 unavailable", e);
        }
    }

    /** Certificate fingerprint: SHA-256 of the full DER certificate (changes on any reissue). */
    public static String fingerprint(X509Certificate certificate) {
        try {
            return Hashing.sha256Hex(certificate.getEncoded());
        } catch (java.security.cert.CertificateEncodingException e) {
            throw invalid("인증서를 인코딩할 수 없습니다.");
        }
    }

    /** SHA-256 of the SubjectPublicKeyInfo DER (stable across reissues of the same key). */
    public static String publicKeySha256(X509Certificate certificate) {
        return Hashing.sha256Hex(certificate.getPublicKey().getEncoded());
    }

    // ------------------------------------------------------------------ signature

    public void verifySignature(X509Certificate leaf, String algorithm, byte[] signedBytes, String signatureBase64) {
        if (algorithm == null || !ALLOWED_ALGORITHMS.contains(algorithm)) {
            throw ApiException.unprocessable("SIGNATURE_ALGORITHM_NOT_ALLOWED",
                    "허용되지 않은 서명 알고리즘입니다 (SHA256withRSA 또는 SHA256withECDSA).");
        }
        PublicKey key = leaf.getPublicKey();
        boolean matches = algorithm.equals(SHA256_WITH_RSA) ? key instanceof RSAPublicKey : key instanceof ECPublicKey;
        if (!matches) {
            throw ApiException.unprocessable("ALGORITHM_KEY_MISMATCH", "서명 알고리즘이 인증서 키 종류와 일치하지 않습니다.");
        }
        byte[] signature = decode(signatureBase64, MAX_SIGNATURE_BYTES, "signature");
        boolean ok;
        try {
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(key);
            verifier.update(signedBytes);
            ok = verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            ok = false;
        }
        if (!ok) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "CERTIFICATE_SIGNATURE_INVALID",
                    "서명이 서버가 발급한 인증 요청 내용과 일치하지 않습니다.");
        }
    }

    // ------------------------------------------------------------------ path + revocation

    public Validation validate(X509Certificate leaf, List<X509Certificate> intermediates) {
        List<X509Certificate> configured = trust.trustAnchors();
        if (configured.isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CERTIFICATE_TRUST_NOT_CONFIGURED",
                    "인증서 신뢰 기관(VISIONCERTI_CERT_TRUST_ANCHORS_FILE)이 설정되지 않았습니다.");
        }
        Instant now = clock.instant();
        try {
            leaf.checkValidity(Date.from(now));
        } catch (GeneralSecurityException e) {
            throw untrusted("인증서 유효기간이 아닙니다.");
        }
        List<X509Certificate> anchors = configured.stream().filter(a -> usableAnchor(a, now)).toList();
        if (anchors.isEmpty()) {
            throw untrusted("사용 가능한(유효기간 내 CA) 신뢰 기관 인증서가 없습니다.");
        }
        PKIXCertPathBuilderResult result = buildPath(leaf, intermediates, anchors, now);
        List<X509Certificate> path = new ArrayList<>();
        result.getCertPath().getCertificates().forEach(c -> path.add((X509Certificate) c));
        path.forEach(CertificateValidator::checkCriticalPolicyQualifiers);
        X509Certificate anchor = result.getTrustAnchor().getTrustedCert();
        // The JDK ignores the anchor's own pathLenConstraint; every non-leaf path element is an intermediate CA.
        if (path.size() - 1 > anchor.getBasicConstraints()) {
            throw untrusted("신뢰 기관의 경로 길이 제한(pathLenConstraint)을 초과했습니다.");
        }
        RevocationStatus revocation = checkRevocation(path, anchor, now);
        return new Validation(leaf, List.copyOf(path), anchor, revocation, now, trust.isMockAnchor(anchor));
    }

    /** Throws the API error matching a non-GOOD revocation status. */
    public static void requireGood(RevocationStatus status) {
        switch (status) {
            case GOOD -> {
            }
            case REVOKED -> throw ApiException.forbidden("CERTIFICATE_REVOKED", "폐지된 인증서입니다.");
            case UNKNOWN, STALE -> throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "CERTIFICATE_REVOCATION_UNAVAILABLE",
                    "인증서 폐지 여부를 확인할 수 없습니다 (" + status + "). 잠시 후 다시 시도해 주세요.", true);
        }
    }

    /** An anchor must be currently valid, a CA, and allowed to sign certificates (keyUsage keyCertSign if present). */
    static boolean usableAnchor(X509Certificate anchor, Instant now) {
        try {
            anchor.checkValidity(Date.from(now));
        } catch (GeneralSecurityException e) {
            return false;
        }
        if (anchor.getBasicConstraints() < 0) {
            return false;
        }
        boolean[] usage = anchor.getKeyUsage();
        return usage == null || (usage.length > 5 && usage[5]);
    }

    private static PKIXCertPathBuilderResult buildPath(X509Certificate leaf, List<X509Certificate> intermediates,
            List<X509Certificate> anchors, Instant now) {
        try {
            Set<TrustAnchor> trustAnchors = new HashSet<>();
            anchors.forEach(a -> trustAnchors.add(new TrustAnchor(a, null)));
            X509CertSelector selector = new X509CertSelector();
            selector.setCertificate(leaf);
            PKIXBuilderParameters params = new PKIXBuilderParameters(trustAnchors, selector);
            params.setRevocationEnabled(false); // revocation is checked explicitly against configured CRLs below
            // Korean joint certificates can mark certificatePolicies critical and include CPS/UserNotice qualifiers.
            // The JDK rejects those by default; inspect and validate every qualifier on the built path below.
            params.setPolicyQualifiersRejected(false);
            params.setDate(Date.from(now));
            params.setMaxPathLength(MAX_CHAIN);
            List<X509Certificate> certs = new ArrayList<>(intermediates);
            certs.add(leaf);
            params.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(certs)));
            return (PKIXCertPathBuilderResult) CertPathBuilder.getInstance("PKIX").build(params);
        } catch (CertPathBuilderException e) {
            throw untrusted("설정된 신뢰 기관까지 유효한 인증서 경로를 만들 수 없습니다.");
        } catch (GeneralSecurityException e) {
            throw untrusted("인증서 경로 검증에 실패했습니다.");
        }
    }

    private static void checkCriticalPolicyQualifiers(X509Certificate certificate) {
        if (certificate.getCriticalExtensionOIDs() == null
                || !certificate.getCriticalExtensionOIDs().contains("2.5.29.32")) return;
        byte[] wrapped = certificate.getExtensionValue("2.5.29.32");
        if (wrapped == null) throw untrusted("필수 인증서 정책을 확인할 수 없습니다.");
        try {
            CertificatePolicies policies = CertificatePolicies.getInstance(
                    ASN1Primitive.fromByteArray(ASN1OctetString.getInstance(wrapped).getOctets()));
            for (PolicyInformation policy : policies.getPolicyInformation()) {
                ASN1Sequence qualifiers = policy.getPolicyQualifiers();
                if (qualifiers == null) continue;
                for (int i = 0; i < qualifiers.size(); i++) {
                    PolicyQualifierInfo qualifier = PolicyQualifierInfo.getInstance(qualifiers.getObjectAt(i));
                    if (PolicyQualifierId.id_qt_cps.equals(qualifier.getPolicyQualifierId())) {
                        String value = DERIA5String.getInstance(qualifier.getQualifier()).getString();
                        URI uri = URI.create(value);
                        if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                                || uri.getHost() == null || value.length() > 2048) {
                            throw untrusted("인증서 정책의 CPS 주소가 올바르지 않습니다.");
                        }
                    } else if (PolicyQualifierId.id_qt_unotice.equals(qualifier.getPolicyQualifierId())) {
                        UserNotice notice = UserNotice.getInstance(qualifier.getQualifier());
                        if (notice.getExplicitText() == null && notice.getNoticeRef() == null) {
                            throw untrusted("인증서 정책의 사용자 안내가 비어 있습니다.");
                        }
                    } else {
                        throw untrusted("지원하지 않는 필수 인증서 정책 안내가 있습니다.");
                    }
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException | java.io.IOException e) {
            throw untrusted("필수 인증서 정책을 해석할 수 없습니다.");
        }
    }

    RevocationStatus checkRevocation(List<X509Certificate> path, X509Certificate anchor, Instant now) {
        List<X509CRL> crls = trust.crls();
        RevocationStatus worst = RevocationStatus.GOOD;
        for (int i = 0; i < path.size(); i++) {
            X509Certificate cert = path.get(i);
            X509Certificate issuer = i + 1 < path.size() ? path.get(i + 1) : anchor;
            RevocationStatus status = statusOf(cert, issuer, crls, now);
            if (status == RevocationStatus.REVOKED) {
                return RevocationStatus.REVOKED;
            }
            if (rank(status) > rank(worst)) {
                worst = status;
            }
        }
        return worst;
    }

    private static int rank(RevocationStatus s) {
        return switch (s) {
            case GOOD -> 0;
            case STALE -> 1;
            case UNKNOWN -> 2;
            case REVOKED -> 3;
        };
    }

    static RevocationStatus statusOf(X509Certificate cert, X509Certificate issuer, List<X509CRL> crls, Instant now) {
        boolean[] issuerUsage = issuer.getKeyUsage();
        if (issuerUsage != null && (issuerUsage.length < 7 || !issuerUsage[6])) {
            return RevocationStatus.UNKNOWN; // issuer may not sign CRLs
        }
        List<X509CRL> authentic = new ArrayList<>();
        for (X509CRL crl : crls) {
            if (!crl.getIssuerX500Principal().equals(cert.getIssuerX500Principal())) {
                continue;
            }
            try {
                crl.verify(issuer.getPublicKey());
            } catch (GeneralSecurityException e) {
                continue; // not signed by this issuer: not evidence at all
            }
            authentic.add(crl);
        }
        // A listing in any authentic, fully understood CRL is definitive, whatever its date. (A listing in a
        // delta/partitioned CRL may be a removeFromCRL entry, so it only yields UNKNOWN below.)
        for (X509CRL crl : authentic) {
            if (!supportedScope(crl)) {
                continue;
            }
            X509CRLEntry entry = crl.getRevokedCertificate(cert);
            if (entry != null && (entry.getRevocationDate() == null
                    || !entry.getRevocationDate().toInstant().isAfter(now))) {
                return RevocationStatus.REVOKED;
            }
        }
        // Signed evidence whose scope this validator cannot interpret: fail closed, never fall back to an older CRL.
        for (X509CRL crl : authentic) {
            if (!supportedScope(crl)) {
                return RevocationStatus.UNKNOWN;
            }
        }
        X509CRL freshest = null;
        for (X509CRL crl : authentic) {
            if (crl.getThisUpdate() == null || crl.getThisUpdate().toInstant().isAfter(now)) {
                continue; // not yet in force
            }
            if (freshest == null || crl.getThisUpdate().after(freshest.getThisUpdate())) {
                freshest = crl;
            }
        }
        if (freshest == null) {
            return RevocationStatus.UNKNOWN;
        }
        if (freshest.getNextUpdate() == null || !now.isBefore(freshest.getNextUpdate().toInstant())) {
            return RevocationStatus.STALE;
        }
        return RevocationStatus.GOOD;
    }

    static final String DELTA_CRL_INDICATOR = "2.5.29.27";
    static final String ISSUING_DISTRIBUTION_POINT = "2.5.29.28";
    static final String FRESHEST_CRL = "2.5.29.46";
    /** Critical CRL extensions that do not change scope: CRL number, authority key identifier. */
    private static final Set<String> HARMLESS_CRITICAL = Set.of("2.5.29.20", "2.5.29.35");

    /** True only for a complete, direct, unpartitioned base CRL without unknown critical (entry) extensions. */
    static boolean supportedScope(X509CRL crl) {
        if (crl.getExtensionValue(DELTA_CRL_INDICATOR) != null
                || crl.getExtensionValue(ISSUING_DISTRIBUTION_POINT) != null
                || crl.getExtensionValue(FRESHEST_CRL) != null) {
            return false;
        }
        Set<String> critical = crl.getCriticalExtensionOIDs();
        if (critical != null && !HARMLESS_CRITICAL.containsAll(critical)) {
            return false;
        }
        Set<? extends X509CRLEntry> entries = crl.getRevokedCertificates();
        if (entries != null) {
            for (X509CRLEntry entry : entries) {
                Set<String> entryCritical = entry.getCriticalExtensionOIDs();
                if ((entryCritical != null && !entryCritical.isEmpty()) || entry.getCertificateIssuer() != null) {
                    return false; // certificateIssuer (indirect CRL) or a critical entry extension
                }
            }
        }
        return true;
    }

    private static byte[] decode(String base64, int maxBytes, String field) {
        if (base64 == null || base64.isBlank() || base64.length() > maxBytes * 2) {
            throw invalid(field + " 값이 비어 있거나 너무 큽니다.");
        }
        try {
            byte[] bytes = Base64.getMimeDecoder().decode(base64);
            if (bytes.length == 0 || bytes.length > maxBytes) {
                throw invalid(field + " 값이 비어 있거나 너무 큽니다.");
            }
            return bytes;
        } catch (IllegalArgumentException e) {
            throw invalid(field + " 값이 올바른 base64가 아닙니다.");
        }
    }

    private static ApiException invalid(String message) {
        return ApiException.unprocessable("CERTIFICATE_INVALID", message);
    }

    private static ApiException untrusted(String message) {
        return ApiException.unprocessable("CERTIFICATE_UNTRUSTED", message);
    }
}
