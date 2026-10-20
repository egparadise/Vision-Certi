package com.inviz.visioncerti.certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inviz.visioncerti.common.ApiException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.CertificatePolicies;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.PolicyInformation;
import org.bouncycastle.asn1.x509.PolicyQualifierId;
import org.bouncycastle.asn1.x509.PolicyQualifierInfo;
import org.bouncycastle.asn1.x509.UserNotice;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

/** Regresses the critical CPS/UserNotice extension used by real Korean joint certificates. */
class CertificatePolicyQualifierTest {
    private final Instant now = Instant.parse("2026-10-21T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final TestPki pki = new TestPki(clock, "Policy Qualifier");

    @Test
    void criticalCpsAndUserNoticeDoNotPreventTrustedFreshCertificate() {
        X509Certificate leaf = leaf(
                new PolicyQualifierInfo("http://example.invalid/cps"),
                new PolicyQualifierInfo(PolicyQualifierId.id_qt_unotice, new UserNotice(null, "Certificate policy notice")));
        CertificateValidator.Validation validation = validator(pki.root.certificate())
                .validate(leaf, List.of());
        assertThat(validation.revocation()).isEqualTo(CertificateValidator.RevocationStatus.GOOD);
        assertThat(validation.path()).containsExactly(leaf);
    }

    @Test
    void unknownCriticalQualifierAndWrongIssuerAreStillRejected() {
        X509Certificate unknown = leaf(new PolicyQualifierInfo(
                new ASN1ObjectIdentifier("1.2.3.4.99"), new UserNotice(null, "unknown")));
        assertThatThrownBy(() -> validator(pki.root.certificate()).validate(unknown, List.of()))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("CERTIFICATE_UNTRUSTED"));

        TestPki other = new TestPki(clock, "Unrelated");
        X509Certificate known = leaf(new PolicyQualifierInfo("http://example.invalid/cps"));
        assertThatThrownBy(() -> validator(other.root.certificate()).validate(known, List.of()))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("CERTIFICATE_UNTRUSTED"));
    }

    private CertificateValidator validator(X509Certificate anchor) {
        X509CRL crl = pki.crl(pki.root, now.minusSeconds(60), now.plus(Duration.ofHours(1)));
        CertificateTrustSource trust = new CertificateTrustSource() {
            @Override public List<X509Certificate> trustAnchors() { return List.of(anchor); }
            @Override public List<X509CRL> crls() { return List.of(crl); }
        };
        return new CertificateValidator(trust, clock);
    }

    private X509Certificate leaf(PolicyQualifierInfo... qualifiers) {
        try {
            KeyPair keys = TestPki.rsa(2048);
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    new X500Name(pki.root.name()), BigInteger.valueOf(98765),
                    Date.from(now.minus(Duration.ofDays(1))), Date.from(now.plus(Duration.ofDays(365))),
                    new X500Name("CN=Synthetic Physician,O=Vision-Certi Test"), keys.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
            builder.addExtension(Extension.certificatePolicies, true,
                    new CertificatePolicies(new PolicyInformation(new ASN1ObjectIdentifier("1.2.3.4"),
                            new DERSequence(qualifiers))));
            return new JcaX509CertificateConverter().getCertificate(
                    builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(pki.root.keys().getPrivate())));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
