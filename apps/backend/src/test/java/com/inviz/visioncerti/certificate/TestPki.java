package com.inviz.visioncerti.certificate;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Synthetic PKI generated at test run time (no private key is stored in the repository): a root CA, an intermediate
 * CA, end-entity certificates and CRLs, all real X.509 signed with real keys.
 */
public final class TestPki {

    public record Ca(String name, KeyPair keys, X509Certificate certificate) {
    }

    public record Leaf(KeyPair keys, X509Certificate certificate) {
        public String der() {
            try {
                return Base64.getEncoder().encodeToString(certificate.getEncoded());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        public String fingerprint() {
            return CertificateValidator.fingerprint(certificate);
        }
    }

    private final Clock clock;
    private final AtomicLong serials = new AtomicLong(100);
    public final Ca root;
    public final Ca intermediate;

    public TestPki(Clock clock, String label) {
        this.clock = clock;
        this.root = selfSignedCa("CN=" + label + " Test Root CA,O=Vision-Certi Test", rsa(2048));
        this.intermediate = subCa("CN=" + label + " Test Issuing CA,O=Vision-Certi Test", rsa(2048), root);
    }

    public static KeyPair rsa(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static KeyPair ec(String curve) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec(curve));
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Ca selfSignedCa(String name, KeyPair keys) {
        Instant now = clock.instant();
        X509Certificate cert = build(new X500Name(name), keys.getPrivate(), new X500Name(name), keys,
                now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(3650)), true,
                KeyUsage.keyCertSign | KeyUsage.cRLSign);
        return new Ca(name, keys, cert);
    }

    public Ca subCa(String name, KeyPair keys, Ca issuer) {
        Instant now = clock.instant();
        X509Certificate cert = build(new X500Name(issuer.name()), issuer.keys().getPrivate(), new X500Name(name), keys,
                now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(1825)), true,
                KeyUsage.keyCertSign | KeyUsage.cRLSign);
        return new Ca(name, keys, cert);
    }

    /** A CA certificate with explicit validity, keyUsage and optional pathLenConstraint (null = unlimited). */
    public Ca ca(String name, KeyPair keys, Ca issuer, Instant notBefore, Instant notAfter, int keyUsage,
            Integer pathLen) {
        X500Name subject = new X500Name(name);
        X500Name issuerName = issuer == null ? subject : new X500Name(issuer.name());
        PrivateKey signer = issuer == null ? keys.getPrivate() : issuer.keys().getPrivate();
        try {
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuerName,
                    BigInteger.valueOf(serials.incrementAndGet()), Date.from(notBefore), Date.from(notAfter), subject,
                    keys.getPublic());
            builder.addExtension(Extension.basicConstraints, true,
                    pathLen == null ? new BasicConstraints(true) : new BasicConstraints(pathLen));
            if (keyUsage != 0) {
                builder.addExtension(Extension.keyUsage, true, new KeyUsage(keyUsage));
            }
            X509Certificate cert = new JcaX509CertificateConverter().getCertificate(
                    builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(signer)));
            return new Ca(name, keys, cert);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Leaf leaf(KeyPair keys) {
        return leaf(keys, intermediate, KeyUsage.digitalSignature | KeyUsage.nonRepudiation,
                clock.instant().minus(Duration.ofDays(1)), clock.instant().plus(Duration.ofDays(365)));
    }

    public Leaf leaf(KeyPair keys, Ca issuer, int keyUsage, Instant notBefore, Instant notAfter) {
        X509Certificate cert = build(new X500Name(issuer.name()), issuer.keys().getPrivate(),
                new X500Name("CN=Dr Test " + serials.get() + ",O=Synthetic Hospital"), keys, notBefore, notAfter,
                false, keyUsage);
        return new Leaf(keys, cert);
    }

    public Leaf leafWithCrlDistributionPoint(KeyPair keys, String uri) {
        Instant now = clock.instant();
        try {
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    new X500Name(intermediate.name()), BigInteger.valueOf(serials.incrementAndGet()),
                    Date.from(now.minus(Duration.ofDays(1))), Date.from(now.plus(Duration.ofDays(365))),
                    new X500Name("CN=Dr Distribution Point,O=Synthetic Hospital"), keys.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
            DistributionPointName name = new DistributionPointName(new GeneralNames(
                    new GeneralName(GeneralName.uniformResourceIdentifier, uri)));
            builder.addExtension(Extension.cRLDistributionPoints, false,
                    new CRLDistPoint(new DistributionPoint[] {new DistributionPoint(name, null, null)}));
            X509Certificate cert = new JcaX509CertificateConverter().getCertificate(
                    builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(intermediate.keys().getPrivate())));
            return new Leaf(keys, cert);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A CRL from {@code issuer}, valid [thisUpdate, nextUpdate), listing the given certificates as revoked. */
    public X509CRL crl(Ca issuer, Instant thisUpdate, Instant nextUpdate, X509Certificate... revoked) {
        return crlSignedBy(issuer.name(), issuer.keys().getPrivate(), thisUpdate, nextUpdate, revoked);
    }

    public X509CRL crlSignedBy(String issuerName, PrivateKey key, Instant thisUpdate, Instant nextUpdate,
            X509Certificate... revoked) {
        return crlWith(issuerName, key, thisUpdate, nextUpdate, b -> { }, revoked);
    }

    /** A CRL whose builder can be customised (extensions, extra entries) before signing. */
    public X509CRL crlWith(String issuerName, PrivateKey key, Instant thisUpdate, Instant nextUpdate,
            Consumer<X509v2CRLBuilder> customize, X509Certificate... revoked) {
        try {
            X509v2CRLBuilder builder = new X509v2CRLBuilder(new X500Name(issuerName), Date.from(thisUpdate));
            builder.setNextUpdate(Date.from(nextUpdate));
            for (X509Certificate cert : revoked) {
                builder.addCRLEntry(cert.getSerialNumber(), Date.from(thisUpdate.minusSeconds(60)),
                        CRLReason.keyCompromise);
            }
            customize.accept(builder);
            String algorithm = key.getAlgorithm().equals("EC") ? "SHA256withECDSA" : "SHA256withRSA";
            return new JcaX509CRLConverter().getCRL(builder.build(new JcaContentSignerBuilder(algorithm).build(key)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Fresh CRLs (one hour) for the root and the intermediate, revoking the given certificates. */
    public List<X509CRL> freshCrls(X509Certificate... revoked) {
        Instant now = clock.instant();
        List<X509Certificate> byRoot = new ArrayList<>();
        List<X509Certificate> byIntermediate = new ArrayList<>();
        for (X509Certificate cert : revoked) {
            if (cert.getIssuerX500Principal().equals(root.certificate().getSubjectX500Principal())) {
                byRoot.add(cert);
            } else {
                byIntermediate.add(cert);
            }
        }
        return List.of(
                crl(root, now.minusSeconds(60), now.plus(Duration.ofHours(1)), byRoot.toArray(X509Certificate[]::new)),
                crl(intermediate, now.minusSeconds(60), now.plus(Duration.ofHours(1)),
                        byIntermediate.toArray(X509Certificate[]::new)));
    }

    public String intermediateDer() {
        try {
            return Base64.getEncoder().encodeToString(intermediate.certificate().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sign(KeyPair keys, String algorithm, byte[] bytes) {
        try {
            Signature signature = Signature.getInstance(algorithm);
            signature.initSign(keys.getPrivate());
            signature.update(bytes);
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String pem(X509Certificate certificate) {
        try {
            return "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                            .encodeToString(certificate.getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private X509Certificate build(X500Name issuer, PrivateKey issuerKey, X500Name subject, KeyPair keys,
            Instant notBefore, Instant notAfter, boolean ca, int keyUsage) {
        try {
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuer,
                    BigInteger.valueOf(serials.incrementAndGet()), Date.from(notBefore), Date.from(notAfter), subject,
                    keys.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
            if (keyUsage != 0) {
                builder.addExtension(Extension.keyUsage, true, new KeyUsage(keyUsage));
            }
            String algorithm = issuerKey.getAlgorithm().equals("EC") ? "SHA256withECDSA" : "SHA256withRSA";
            return new JcaX509CertificateConverter().getCertificate(
                    builder.build(new JcaContentSignerBuilder(algorithm).build(issuerKey)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
