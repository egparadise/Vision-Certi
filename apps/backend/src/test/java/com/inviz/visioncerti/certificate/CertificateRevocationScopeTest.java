package com.inviz.visioncerti.certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inviz.visioncerti.MutableClock;
import com.inviz.visioncerti.certificate.CertificateValidator.RevocationStatus;
import com.inviz.visioncerti.common.ApiException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.CRLNumber;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.ExtensionsGenerator;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.IssuingDistributionPoint;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.ReasonFlags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CRL scope and trust-anchor checks: a signed CRL whose scope the validator does not interpret must never yield GOOD,
 * and must not be skipped in favour of an older complete CRL.
 */
class CertificateRevocationScopeTest {

    private MutableClock clock;
    private TestPki pki;
    private CertificateEnrollmentServiceTest.TestTrust trust;
    private CertificateValidator validator;
    private TestPki.Leaf leaf;
    private Instant now;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.now());
        now = clock.instant();
        pki = new TestPki(clock, "Scope");
        trust = new CertificateEnrollmentServiceTest.TestTrust();
        trust.anchors.add(pki.root.certificate());
        validator = new CertificateValidator(trust, clock);
        leaf = pki.leaf(TestPki.rsa(2048));
    }

    /** Fresh complete root CRL + the given CRLs for the issuing CA. */
    private RevocationStatus statusWith(X509CRL... issuingCaCrls) {
        trust.crls.clear();
        trust.crls.add(pki.crl(pki.root, now.minusSeconds(60), now.plus(Duration.ofHours(1))));
        trust.crls.addAll(List.of(issuingCaCrls));
        return validator.validate(leaf.certificate(), List.of(pki.intermediate.certificate())).revocation();
    }

    private X509CRL fullCrl(Instant thisUpdate, X509Certificate... revoked) {
        return pki.crl(pki.intermediate, thisUpdate, now.plus(Duration.ofHours(1)), revoked);
    }

    private X509CRL issuingCrl(Instant thisUpdate, java.util.function.Consumer<org.bouncycastle.cert.X509v2CRLBuilder> c,
            X509Certificate... revoked) {
        return pki.crlWith(pki.intermediate.name(), pki.intermediate.keys().getPrivate(), thisUpdate,
                now.plus(Duration.ofHours(1)), c, revoked);
    }

    private static void ext(org.bouncycastle.cert.X509v2CRLBuilder b, ASN1ObjectIdentifier oid, boolean critical,
            org.bouncycastle.asn1.ASN1Encodable value) {
        try {
            b.addExtension(oid, critical, value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void completeFreshCrlIsGoodAndListedIsRevoked() {
        assertThat(statusWith(fullCrl(now.minusSeconds(60)))).isEqualTo(RevocationStatus.GOOD);
        assertThat(statusWith(fullCrl(now.minusSeconds(60), leaf.certificate()))).isEqualTo(RevocationStatus.REVOKED);
        // CRL number (non-critical) is understood.
        assertThat(statusWith(issuingCrl(now.minusSeconds(60),
                b -> ext(b, Extension.cRLNumber, false, new CRLNumber(BigInteger.TEN)))))
                .isEqualTo(RevocationStatus.GOOD);
    }

    @Test
    void signedFreshEmptyDeltaCrlIsUnknownNotGoodAndNotSkippedForOlderBase() {
        X509CRL base = fullCrl(now.minus(Duration.ofMinutes(30)));
        X509CRL delta = issuingCrl(now.minusSeconds(30),
                b -> ext(b, Extension.deltaCRLIndicator, true, new CRLNumber(BigInteger.ONE)));
        assertThat(statusWith(delta)).isEqualTo(RevocationStatus.UNKNOWN);
        assertThat(statusWith(base, delta)).isEqualTo(RevocationStatus.UNKNOWN);
    }

    @Test
    void partitionedReasonScopedOrIndirectIdpCrlIsUnknown() {
        DistributionPointName dp = new DistributionPointName(new GeneralNames(
                new GeneralName(GeneralName.uniformResourceIdentifier, "ldap://partition-7")));
        X509CRL partition = issuingCrl(now.minusSeconds(30), b -> ext(b, Extension.issuingDistributionPoint, true,
                new IssuingDistributionPoint(dp, false, false, null, false, false)));
        X509CRL reasons = issuingCrl(now.minusSeconds(30), b -> ext(b, Extension.issuingDistributionPoint, true,
                new IssuingDistributionPoint(null, false, false, new ReasonFlags(ReasonFlags.affiliationChanged),
                        false, false)));
        X509CRL indirect = issuingCrl(now.minusSeconds(30), b -> ext(b, Extension.issuingDistributionPoint, true,
                new IssuingDistributionPoint(null, false, false, null, true, false)));
        X509CRL olderComplete = fullCrl(now.minus(Duration.ofMinutes(30)));
        for (X509CRL scoped : List.of(partition, reasons, indirect)) {
            assertThat(statusWith(scoped)).isEqualTo(RevocationStatus.UNKNOWN);
            assertThat(statusWith(olderComplete, scoped)).isEqualTo(RevocationStatus.UNKNOWN);
        }
        // Advertising deltas (freshestCRL) is also not understood.
        X509CRL withFreshest = issuingCrl(now.minusSeconds(30), b -> ext(b, Extension.freshestCRL, false,
                new CRLDistPoint(new DistributionPoint[] {new DistributionPoint(dp, null, null)})));
        assertThat(statusWith(withFreshest)).isEqualTo(RevocationStatus.UNKNOWN);
    }

    @Test
    void issuerDistributionPointMustMatchCertificateDistributionPoint() {
        String uri = "ldap://ca.example.invalid/ou=issuer?certificateRevocationList";
        X509Certificate withDp = pki.leafWithCrlDistributionPoint(TestPki.rsa(2048), uri).certificate();
        DistributionPointName matchingName = new DistributionPointName(new GeneralNames(
                new GeneralName(GeneralName.uniformResourceIdentifier, uri)));
        X509CRL matching = issuingCrl(now.minusSeconds(30), b -> ext(b,
                Extension.issuingDistributionPoint, true,
                new IssuingDistributionPoint(matchingName, false, false, null, false, false)));
        assertThat(CertificateValidator.statusOf(withDp, pki.intermediate.certificate(), List.of(matching), now))
                .isEqualTo(RevocationStatus.GOOD);
        X509CRL revoked = issuingCrl(now.minusSeconds(30), b -> ext(b,
                Extension.issuingDistributionPoint, true,
                new IssuingDistributionPoint(matchingName, false, false, null, false, false)), withDp);
        assertThat(CertificateValidator.statusOf(withDp, pki.intermediate.certificate(), List.of(revoked), now))
                .isEqualTo(RevocationStatus.REVOKED);

        DistributionPointName otherName = new DistributionPointName(new GeneralNames(
                new GeneralName(GeneralName.uniformResourceIdentifier, "ldap://ca.example.invalid/other")));
        X509CRL other = issuingCrl(now.minusSeconds(30), b -> ext(b,
                Extension.issuingDistributionPoint, true,
                new IssuingDistributionPoint(otherName, false, false, null, false, false)));
        assertThat(CertificateValidator.statusOf(withDp, pki.intermediate.certificate(), List.of(other), now))
                .isEqualTo(RevocationStatus.UNKNOWN);
        assertThat(CertificateValidator.statusOf(withDp, pki.intermediate.certificate(), List.of(other, matching), now))
                .isEqualTo(RevocationStatus.GOOD);
        assertThat(CertificateValidator.statusOf(withDp, pki.intermediate.certificate(), List.of(other, revoked), now))
                .isEqualTo(RevocationStatus.REVOKED);
    }

    @Test
    void unknownCriticalCrlOrEntryExtensionIsUnknown() {
        X509CRL criticalCrlExt = issuingCrl(now.minusSeconds(30),
                b -> ext(b, new ASN1ObjectIdentifier("1.3.6.1.4.1.99999.1"), true, DERNull.INSTANCE));
        assertThat(statusWith(criticalCrlExt)).isEqualTo(RevocationStatus.UNKNOWN);
        assertThat(statusWith(fullCrl(now.minus(Duration.ofMinutes(30))), criticalCrlExt))
                .isEqualTo(RevocationStatus.UNKNOWN);

        // A critical extension on some (other) revoked entry, e.g. certificateIssuer of an indirect CRL.
        X509CRL criticalEntry = issuingCrl(now.minusSeconds(30), b -> {
            try {
                ExtensionsGenerator g = new ExtensionsGenerator();
                g.addExtension(Extension.certificateIssuer, true, new GeneralNames(
                        new GeneralName(new X500Name("CN=Other CA"))));
                Extensions extensions = g.generate();
                b.addCRLEntry(BigInteger.valueOf(999_999), Date.from(now.minusSeconds(120)), extensions);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(statusWith(criticalEntry)).isEqualTo(RevocationStatus.UNKNOWN);
        X509CRL unknownEntryExt = issuingCrl(now.minusSeconds(30), b -> {
            try {
                ExtensionsGenerator g = new ExtensionsGenerator();
                g.addExtension(new ASN1ObjectIdentifier("1.3.6.1.4.1.99999.2"), true, DERNull.INSTANCE);
                b.addCRLEntry(BigInteger.valueOf(888_888), Date.from(now.minusSeconds(120)), g.generate());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(statusWith(unknownEntryExt)).isEqualTo(RevocationStatus.UNKNOWN);
    }

    @Test
    void forgedScopedCrlIsIgnoredButRevocationInCompleteCrlStillWins() {
        // Signed by a different key under the same name: not evidence, so the authentic complete CRL governs.
        KeyPair impostor = TestPki.rsa(2048);
        X509CRL forgedDelta = pki.crlWith(pki.intermediate.name(), impostor.getPrivate(), now.minusSeconds(10),
                now.plus(Duration.ofHours(1)),
                b -> ext(b, Extension.deltaCRLIndicator, true, new CRLNumber(BigInteger.ONE)));
        assertThat(statusWith(fullCrl(now.minusSeconds(60)), forgedDelta)).isEqualTo(RevocationStatus.GOOD);
        // Revoked in the complete CRL even when a newer authentic delta omits it → still never GOOD.
        X509CRL delta = issuingCrl(now.minusSeconds(10), b -> {
            ext(b, Extension.deltaCRLIndicator, true, new CRLNumber(BigInteger.ONE));
            b.addCRLEntry(BigInteger.valueOf(777), Date.from(now.minusSeconds(60)), CRLReason.removeFromCRL);
        });
        assertThat(statusWith(fullCrl(now.minusSeconds(60), leaf.certificate()), delta))
                .isEqualTo(RevocationStatus.REVOKED);
    }

    @Test
    void expiredNonCaOrNonSigningTrustAnchorsAreNotUsed() {
        trust.crls.addAll(pki.freshCrls());
        Instant past = now.minus(Duration.ofDays(400));
        TestPki.Ca expiredRoot = pki.ca("CN=Expired Root,O=Vision-Certi Test", TestPki.rsa(2048), null,
                past, now.minus(Duration.ofDays(1)), KeyUsage.keyCertSign | KeyUsage.cRLSign, null);
        assertAnchorRejected(expiredRoot);
        TestPki.Ca notYetValid = pki.ca("CN=Future Root,O=Vision-Certi Test", TestPki.rsa(2048), null,
                now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(400)), KeyUsage.keyCertSign | KeyUsage.cRLSign,
                null);
        assertAnchorRejected(notYetValid);
        TestPki.Ca noCertSign = pki.ca("CN=NoCertSign Root,O=Vision-Certi Test", TestPki.rsa(2048), null,
                now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(400)), KeyUsage.cRLSign, null);
        assertAnchorRejected(noCertSign);
    }

    private void assertAnchorRejected(TestPki.Ca root) {
        TestPki.Ca issuing = pki.ca("CN=Issuing under " + root.name().substring(3, 10) + ",O=Vision-Certi Test",
                TestPki.rsa(2048), root, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(300)),
                KeyUsage.keyCertSign | KeyUsage.cRLSign, null);
        TestPki.Leaf l = pki.leaf(TestPki.rsa(2048), issuing, KeyUsage.digitalSignature,
                now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(30)));
        trust.anchors.clear();
        trust.anchors.add(root.certificate());
        trust.crls.clear();
        trust.crls.add(pki.crl(root, now.minusSeconds(60), now.plus(Duration.ofHours(1))));
        trust.crls.add(pki.crl(issuing, now.minusSeconds(60), now.plus(Duration.ofHours(1))));
        assertThatThrownBy(() -> validator.validate(l.certificate(), List.of(issuing.certificate())))
                .isInstanceOf(ApiException.class)
                .satisfies(t -> assertThat(((ApiException) t).code()).isEqualTo("CERTIFICATE_UNTRUSTED"));
    }

    @Test
    void anchorPathLengthConstraintIsEnforced() {
        TestPki.Ca root = pki.ca("CN=PathLen0 Root,O=Vision-Certi Test", TestPki.rsa(2048), null,
                now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(400)), KeyUsage.keyCertSign | KeyUsage.cRLSign,
                0);
        TestPki.Ca issuing = pki.ca("CN=Issuing Under PathLen0,O=Vision-Certi Test", TestPki.rsa(2048), root,
                now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(300)), KeyUsage.keyCertSign | KeyUsage.cRLSign,
                null);
        TestPki.Leaf l = pki.leaf(TestPki.rsa(2048), issuing, KeyUsage.digitalSignature,
                now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(30)));
        trust.anchors.clear();
        trust.anchors.add(root.certificate());
        trust.crls.clear();
        trust.crls.add(pki.crl(root, now.minusSeconds(60), now.plus(Duration.ofHours(1))));
        trust.crls.add(pki.crl(issuing, now.minusSeconds(60), now.plus(Duration.ofHours(1))));
        assertThatThrownBy(() -> validator.validate(l.certificate(), List.of(issuing.certificate())))
                .satisfies(t -> assertThat(((ApiException) t).code()).isEqualTo("CERTIFICATE_UNTRUSTED"));
    }

    @Test
    void certificateWithTrailingDataIsRejected() throws Exception {
        byte[] der = leaf.certificate().getEncoded();
        byte[] second = pki.intermediate.certificate().getEncoded();
        byte[] withTrailingCert = new byte[der.length + second.length];
        System.arraycopy(der, 0, withTrailingCert, 0, der.length);
        System.arraycopy(second, 0, withTrailingCert, der.length, second.length);
        byte[] key = leaf.keys().getPrivate().getEncoded();
        byte[] withTrailingKey = new byte[der.length + key.length];
        System.arraycopy(der, 0, withTrailingKey, 0, der.length);
        System.arraycopy(key, 0, withTrailingKey, der.length, key.length);
        for (byte[] bytes : List.of(withTrailingCert, withTrailingKey)) {
            assertThatThrownBy(() -> validator.parse(Base64.getEncoder().encodeToString(bytes)))
                    .satisfies(t -> assertThat(((ApiException) t).code()).isEqualTo("CERTIFICATE_INVALID"));
        }
        assertThat(validator.parse(Base64.getEncoder().encodeToString(der))).isEqualTo(leaf.certificate());
    }
}
