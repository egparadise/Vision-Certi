package com.inviz.visioncerti.certificate;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * DEVELOPMENT ONLY, opt-in ({@code VISIONCERTI_CERT_DEV_MOCK_ENABLED=true} in the dev profile). An in-memory synthetic
 * CA ("MOCK_CERTIFICATE — NOT A REAL CA") that issues one EC P-256 certificate per doctor and keeps the private keys in
 * this JVM only. It lets the UI exercise enrollment/login without a PC certificate agent. Proofs it signs still go
 * through the normal path + CRL + review + consent pipeline and are labeled MOCK_CERTIFICATE. Nothing here is trusted
 * outside this process.
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "visioncerti.cert.dev-mock-enabled", havingValue = "true")
public class DevMockCertificateAuthority implements CertificateTrustSource {

    public static final String CA_NAME = "CN=MOCK_CERTIFICATE CA - NOT A REAL CA,O=Vision-Certi DEV";

    private record Issued(X509Certificate certificate, KeyPair keys) {
    }

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final KeyPair caKeys;
    private final X509Certificate caCertificate;
    private final Map<UUID, Issued> byDoctor = new HashMap<>();
    private final Map<String, Issued> byFingerprint = new HashMap<>();

    public DevMockCertificateAuthority(Clock clock) {
        this.clock = clock;
        this.caKeys = ec();
        Instant now = clock.instant();
        try {
            X500Name name = new X500Name(CA_NAME);
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name, serial(),
                    Date.from(now.minus(Duration.ofMinutes(5))), Date.from(now.plus(Duration.ofDays(365))), name,
                    caKeys.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            this.caCertificate = new JcaX509CertificateConverter().getCertificate(builder.build(signer()));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create the dev mock CA", e);
        }
    }

    @Override
    public List<X509Certificate> trustAnchors() {
        return List.of(caCertificate);
    }

    /** A fresh CRL (valid one hour) signed by the mock CA on every call; nothing is ever revoked here. */
    @Override
    public synchronized List<X509CRL> crls() {
        Instant now = clock.instant();
        try {
            X509v2CRLBuilder builder = new X509v2CRLBuilder(new X500Name(CA_NAME), Date.from(now.minusSeconds(1)));
            builder.setNextUpdate(Date.from(now.plus(Duration.ofHours(1))));
            return List.of(new JcaX509CRLConverter().getCRL(builder.build(signer())));
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public boolean isMockAnchor(X509Certificate anchor) {
        return caCertificate.equals(anchor);
    }

    public synchronized X509Certificate certificateFor(UUID doctorId) {
        return byDoctor.computeIfAbsent(doctorId, this::issue).certificate();
    }

    public synchronized boolean knows(String fingerprint) {
        return byFingerprint.containsKey(fingerprint);
    }

    public synchronized X509Certificate certificateByFingerprint(String fingerprint) {
        Issued issued = byFingerprint.get(fingerprint);
        return issued == null ? null : issued.certificate();
    }

    /** SHA256withECDSA over exactly these bytes with the mock key of this certificate. */
    public synchronized byte[] sign(String fingerprint, byte[] bytes) {
        Issued issued = byFingerprint.get(fingerprint);
        if (issued == null) {
            throw new IllegalArgumentException("unknown mock certificate");
        }
        try {
            Signature signature = Signature.getInstance(CertificateValidator.SHA256_WITH_ECDSA);
            signature.initSign(issued.keys().getPrivate());
            signature.update(bytes);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private Issued issue(UUID doctorId) {
        KeyPair keys = ec();
        Instant now = clock.instant();
        try {
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(new X500Name(CA_NAME), serial(),
                    Date.from(now.minus(Duration.ofMinutes(5))), Date.from(now.plus(Duration.ofDays(90))),
                    new X500Name("CN=MOCK_CERTIFICATE " + doctorId + ",O=Vision-Certi DEV"), keys.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation));
            X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(builder.build(signer()));
            Issued issued = new Issued(certificate, keys);
            byFingerprint.put(CertificateValidator.fingerprint(certificate), issued);
            return issued;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot issue a mock certificate", e);
        }
    }

    private ContentSigner signer() throws OperatorCreationException {
        return new JcaContentSignerBuilder("SHA256withECDSA").build(caKeys.getPrivate());
    }

    private BigInteger serial() {
        return new BigInteger(64, random).add(BigInteger.ONE);
    }

    private static KeyPair ec() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
