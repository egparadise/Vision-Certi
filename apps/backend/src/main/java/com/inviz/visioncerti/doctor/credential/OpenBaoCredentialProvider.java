package com.inviz.visioncerti.doctor.credential;

import com.inviz.visioncerti.common.Hashing;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCSException;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

/**
 * OpenBao Transit (non-exportable ECDSA P-256 key per physician) + OpenBao PKI (X.509 certificate for that same
 * public key, issued from a CSR whose proof-of-possession signature is produced by Transit). The provisioning
 * token manages keys and certificates; the signing token can only call Transit sign.
 *
 * <p>OpenBao Transit is software key storage unless OpenBao itself is backed by an HSM; nothing here claims HSM
 * protection. The PKI mount is a private CA.
 */
public final class OpenBaoCredentialProvider implements DoctorCredentialProvider {

    public static final String SIGNATURE_MODE = "OPENBAO_TRANSIT_ECDSA_P256_DEV_NOT_FOR_CLINICAL_USE";
    /**
     * Label used in prod: an OpenBao Transit ECDSA P-256 signature with a private OpenBao PKI certificate, no trusted
     * timestamp and no accredited CA. It describes the mechanism; it is not a claim of legal validity.
     */
    public static final String PROD_SIGNATURE_MODE = "OPENBAO_TRANSIT_ECDSA_P256_PRIVATE_PKI_NOT_TIMESTAMPED";
    private static final Pattern TRANSIT_SIGNATURE = Pattern.compile("[a-z]+:v(\\d+):([A-Za-z0-9+/]+=*)");

    private final OpenBaoProperties properties;
    private final OpenBaoClient client;
    private final Clock clock;
    private final String signatureMode;

    public OpenBaoCredentialProvider(OpenBaoProperties properties, Clock clock) {
        this(properties, clock, SIGNATURE_MODE);
    }

    public OpenBaoCredentialProvider(OpenBaoProperties properties, Clock clock, String signatureMode) {
        String problem = properties.problem();
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        this.properties = properties;
        this.client = new OpenBaoClient(properties);
        this.clock = clock;
        this.signatureMode = signatureMode;
    }

    @Override
    public String backend() {
        return OPENBAO_TRANSIT;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String unavailableReason() {
        return null;
    }

    @Override
    public String signatureMode() {
        return signatureMode;
    }

    @Override
    public boolean cryptographicallyAttributable() {
        return true;
    }

    @Override
    public boolean checksRevocationRemotely() {
        return true;
    }

    public String keyNameFor(String doctorId) {
        return properties.keyPrefix() + "-" + doctorId;
    }

    @Override
    public DoctorCredential provision(String doctorId) {
        String keyName = keyNameFor(doctorId);
        String keyPath = properties.transitMount() + "/keys/" + keyName;

        // 1. Key: reuse when it exists (idempotent retry), otherwise create it non-exportable.
        Map<String, Object> key = client.get(keyPath, properties.provisioningToken());
        if (key == null) {
            Map<String, Object> create = new LinkedHashMap<>();
            create.put("type", "ecdsa-p256");
            create.put("exportable", false);
            create.put("allow_plaintext_backup", false);
            client.post(keyPath, properties.provisioningToken(), create);
            key = client.get(keyPath, properties.provisioningToken());
            if (key == null) {
                throw new CredentialException(CredentialException.Kind.UNAVAILABLE, "Transit key not readable after create");
            }
        }
        Map<String, Object> data = map(key.get("data"));
        if (!"ecdsa-p256".equals(data.get("type"))) {
            throw new CredentialException(CredentialException.Kind.KEY_POLICY_VIOLATION,
                    "Existing Transit key is not ecdsa-p256");
        }
        if (!Boolean.FALSE.equals(data.get("exportable"))
                || Boolean.TRUE.equals(data.get("allow_plaintext_backup"))) {
            throw new CredentialException(CredentialException.Kind.KEY_POLICY_VIOLATION,
                    "Transit key must be non-exportable without plaintext backup");
        }
        int version = intValue(data.get("latest_version"));
        Map<String, Object> versions = map(data.get("keys"));
        Object publicKeyPem = map(versions.get(Integer.toString(version))).get("public_key");
        if (!(publicKeyPem instanceof String pem) || pem.isBlank()) {
            throw new CredentialException(CredentialException.Kind.UNAVAILABLE, "Transit key has no public key");
        }
        ECPublicKey transitKey = parsePublicKey(pem);

        // 2. CSR signed through Transit (proof that the key that will sign is the one being certified).
        String csrPem = csrPem(keyName, version, transitKey);

        // 3. Certificate from OpenBao PKI for exactly that public key.
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("csr", csrPem);
        issue.put("common_name", keyName);
        issue.put("ttl", properties.certTtl());
        issue.put("format", "pem");
        issue.put("exclude_cn_from_sans", true);
        Map<String, Object> issued = map(client.post(properties.pkiMount() + "/sign/" + properties.pkiRole(),
                properties.provisioningToken(), issue).get("data"));
        if (!(issued.get("certificate") instanceof String certificatePem)
                || !(issued.get("serial_number") instanceof String serial)) {
            throw new CredentialException(CredentialException.Kind.UNAVAILABLE, "PKI sign response lacks certificate");
        }
        X509Certificate certificate = parseCertificate(certificatePem);
        if (!sameKey(transitKey, certificate.getPublicKey())) {
            throw new CredentialException(CredentialException.Kind.KEY_MISMATCH,
                    "Issued certificate public key does not match the Transit key");
        }
        if (!new BigInteger(serial.replace(":", "").replace("-", ""), 16).equals(certificate.getSerialNumber())) {
            throw new CredentialException(CredentialException.Kind.UNAVAILABLE, "PKI serial number does not match certificate");
        }
        verifyChain(certificate, issued);
        return new DoctorCredential(OPENBAO_TRANSIT, keyName, version,
                Base64.getEncoder().encodeToString(certificate.getPublicKey().getEncoded()), DoctorCredential.KIND_X509,
                certificatePem.strip(), serial, certificate.getSubjectX500Principal().getName(),
                certificate.getIssuerX500Principal().getName(), certificate.getNotBefore().toInstant(),
                certificate.getNotAfter().toInstant());
    }

    /**
     * The certificate must be signed by the returned issuing CA (issuer DN match + signature), each certificate in
     * {@code ca_chain} must be signed by the next one, and all must be within validity now. This checks internal
     * consistency of a private CA chain; it does not make that CA trusted.
     */
    private void verifyChain(X509Certificate certificate, Map<String, Object> issued) {
        if (!(issued.get("issuing_ca") instanceof String caPem) || caPem.isBlank()) {
            throw new CredentialException(CredentialException.Kind.UNAVAILABLE, "PKI sign response lacks issuing_ca");
        }
        List<X509Certificate> chain = new ArrayList<>();
        chain.add(certificate);
        chain.add(parseCertificate(caPem));
        if (issued.get("ca_chain") instanceof List<?> extra) {
            for (Object element : extra) {
                if (element instanceof String pem && !pem.isBlank()) {
                    X509Certificate next = parseCertificate(pem);
                    if (!chain.contains(next)) {
                        chain.add(next);
                    }
                }
            }
        }
        Date now = Date.from(clock.instant());
        for (int i = 0; i < chain.size(); i++) {
            X509Certificate current = chain.get(i);
            try {
                current.checkValidity(now);
            } catch (GeneralSecurityException e) {
                throw new CredentialException(CredentialException.Kind.CERTIFICATE_NOT_VALID,
                        i == 0 ? "Issued certificate is not currently valid" : "A CA certificate in the chain is not valid");
            }
            boolean last = i + 1 == chain.size();
            X509Certificate issuer = last ? current : chain.get(i + 1);
            if (!current.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())) {
                if (last) {
                    break; // the returned chain ends below a root that OpenBao did not include
                }
                throw new CredentialException(CredentialException.Kind.UNAVAILABLE,
                        "Certificate chain issuer names do not link");
            }
            try {
                current.verify(issuer.getPublicKey());
            } catch (GeneralSecurityException e) {
                throw new CredentialException(CredentialException.Kind.UNAVAILABLE,
                        i == 0 ? "Issued certificate is not signed by the reported issuing CA"
                                : "CA chain signature does not verify");
            }
        }
    }

    @Override
    public byte[] signDigest(DoctorCredential credential, byte[] sha256Digest) {
        if (sha256Digest.length != 32) {
            throw new IllegalArgumentException("SHA-256 digest must be 32 bytes");
        }
        byte[] der = transitSign(credential.keyName(), credential.keyVersion(), sha256Digest);
        if (!verifyDigest(parsePublicKey(credential.publicKeyDer()), sha256Digest, der)) {
            throw new CredentialException(CredentialException.Kind.KEY_MISMATCH,
                    "Transit signature does not verify with the certified public key");
        }
        return der;
    }

    @Override
    public void assertNotRevoked(DoctorCredential credential) {
        Map<String, Object> body = client.get(properties.pkiMount() + "/cert/" + credential.serialNumber(), null);
        if (body == null) {
            throw new CredentialException(CredentialException.Kind.UNAVAILABLE, "Certificate not found in PKI");
        }
        Object revocationTime = map(body.get("data")).get("revocation_time");
        if (revocationTime instanceof Number n && n.longValue() > 0) {
            throw new CredentialException(CredentialException.Kind.CERTIFICATE_NOT_VALID, "Certificate is revoked in PKI");
        }
        if (!(revocationTime instanceof Number)) {
            throw new CredentialException(CredentialException.Kind.UNAVAILABLE, "PKI did not report revocation status");
        }
    }

    @Override
    public void revoke(DoctorCredential credential) {
        client.post(properties.pkiMount() + "/revoke", properties.provisioningToken(),
                Map.of("serial_number", credential.serialNumber()));
    }

    private byte[] transitSign(String keyName, int keyVersion, byte[] sha256Digest) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", Base64.getEncoder().encodeToString(sha256Digest));
        body.put("prehashed", true);
        body.put("hash_algorithm", "sha2-256");
        body.put("marshaling_algorithm", "asn1");
        body.put("key_version", keyVersion);
        Map<String, Object> data = map(client.post(properties.transitMount() + "/sign/" + keyName,
                properties.signingToken(), body).get("data"));
        if (!(data.get("signature") instanceof String value)) {
            throw new CredentialException(CredentialException.Kind.UNAVAILABLE, "Transit sign response lacks signature");
        }
        Matcher m = TRANSIT_SIGNATURE.matcher(value);
        if (!m.matches() || Integer.parseInt(m.group(1)) != keyVersion) {
            throw new CredentialException(CredentialException.Kind.UNAVAILABLE,
                    "Transit signature is malformed or not from the pinned key version");
        }
        return Base64.getDecoder().decode(m.group(2));
    }

    private String csrPem(String keyName, int version, ECPublicKey publicKey) {
        try {
            PKCS10CertificationRequest csr = new JcaPKCS10CertificationRequestBuilder(
                    new X500Name("CN=" + keyName + ",O=Vision-Certi DEV"), publicKey)
                    .build(new TransitContentSigner(digest -> transitSign(keyName, version, digest)));
            if (!csr.isSignatureValid(new JcaContentVerifierProviderBuilder().build(publicKey))) {
                throw new CredentialException(CredentialException.Kind.KEY_MISMATCH,
                        "CSR signed by Transit does not verify with the Transit public key");
            }
            return "-----BEGIN CERTIFICATE REQUEST-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(csr.getEncoded())
                    + "\n-----END CERTIFICATE REQUEST-----\n";
        } catch (OperatorCreationException | PKCSException | java.io.IOException e) {
            throw new CredentialException(CredentialException.Kind.UNAVAILABLE, "Could not build CSR", e);
        }
    }

    static boolean verifyDigest(PublicKey key, byte[] sha256Digest, byte[] der) {
        try {
            Signature verifier = Signature.getInstance("NONEwithECDSA");
            verifier.initVerify(key);
            verifier.update(sha256Digest);
            return verifier.verify(der);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    static boolean sameKey(ECPublicKey expected, PublicKey actual) {
        return actual instanceof ECPublicKey ec
                && ec.getW().equals(expected.getW())
                && ec.getParams().getCurve().equals(expected.getParams().getCurve());
    }

    static ECPublicKey parsePublicKey(String pem) {
        String body = pem.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
        try {
            return parsePublicKey(Base64.getDecoder().decode(body));
        } catch (IllegalArgumentException e) {
            throw new CredentialException(CredentialException.Kind.UNAVAILABLE, "Transit public key is not valid PEM");
        }
    }

    static ECPublicKey parsePublicKey(byte[] spki) {
        try {
            PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki));
            if (!(key instanceof ECPublicKey ec) || ec.getParams().getCurve().getField().getFieldSize() != 256) {
                throw new CredentialException(CredentialException.Kind.KEY_POLICY_VIOLATION, "Key is not EC P-256");
            }
            return ec;
        } catch (GeneralSecurityException e) {
            throw new CredentialException(CredentialException.Kind.UNAVAILABLE, "Public key could not be parsed");
        }
    }

    private static X509Certificate parseCertificate(String pem) {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw new CredentialException(CredentialException.Kind.UNAVAILABLE, "PKI returned an unparsable certificate");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new CredentialException(CredentialException.Kind.UNAVAILABLE, "Unexpected OpenBao response shape");
    }

    private static int intValue(Object value) {
        if (value instanceof Number n && n.intValue() > 0) {
            return n.intValue();
        }
        throw new CredentialException(CredentialException.Kind.UNAVAILABLE, "Transit key has no latest_version");
    }

    /** BouncyCastle ContentSigner whose private-key operation is a Transit prehashed sign. */
    static final class TransitContentSigner implements ContentSigner {

        interface RemoteDigestSigner {
            byte[] sign(byte[] sha256Digest);
        }

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final RemoteDigestSigner remote;

        TransitContentSigner(RemoteDigestSigner remote) {
            this.remote = remote;
        }

        @Override
        public AlgorithmIdentifier getAlgorithmIdentifier() {
            return new AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256);
        }

        @Override
        public OutputStream getOutputStream() {
            return buffer;
        }

        @Override
        public byte[] getSignature() {
            return remote.sign(Hashing.sha256(buffer.toByteArray()));
        }
    }
}
