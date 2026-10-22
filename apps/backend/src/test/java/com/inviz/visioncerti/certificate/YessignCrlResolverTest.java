package com.inviz.visioncerti.certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.inviz.visioncerti.MutableClock;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.bouncycastle.asn1.x509.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class YessignCrlResolverTest {
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "VISIONCERTI_YESSIGN_LIVE_CA_FILE", matches = ".+")
    void liveProviderTransportReturnsIssuerSignedCurrentNamedCrl() throws Exception {
        var issuer = CertificateValidator.parseDer(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of(System.getenv("VISIONCERTI_YESSIGN_LIVE_CA_FILE"))));
        String liveDp = DP.replace("dp7p12345", "dp6p47981");
        var syntheticDpCarrier = pki.leafWithCrlDistributionPoint(TestPki.rsa(2048), liveDp);
        var resolver = new YessignCrlResolver(java.time.Clock.systemUTC());
        var result = resolver.resolve(syntheticDpCarrier.certificate(), issuer);
        assertThat(result).hasSize(1);
        result.getFirst().verify(issuer.getPublicKey());
        assertThat(result.getFirst().getNextUpdate().toInstant()).isAfter(Instant.now());
        // Public CRL transport/signature test only; the synthetic carrier is not trusted for authentication.
    }
    private static final String DP = "ldap://ds.yessign.or.kr:389/ou=dp7p12345,ou=AccreditedCA,o=yessign,c=kr?certificateRevocationList";
    private MutableClock clock;
    private TestPki pki;
    private TestPki.Leaf leaf;
    private CertificateEnrollmentServiceTest.TestTrust trust;
    @BeforeEach void setup() {
        clock = new MutableClock(Instant.parse("2026-10-22T12:00:00Z"));
        pki = new TestPki(clock, "CRL partitions");
        leaf = pki.leafWithCrlDistributionPoint(TestPki.rsa(2048), DP);
        trust = new CertificateEnrollmentServiceTest.TestTrust();
        trust.anchors.add(pki.root.certificate());
        trust.crls.add(pki.crl(pki.root, clock.instant().minusSeconds(60), clock.instant().plusSeconds(3600)));
    }
    private java.security.cert.X509CRL partition(String dp, Instant next, java.security.cert.X509Certificate... revoked) {
        var name = new DistributionPointName(new GeneralNames(new GeneralName(GeneralName.uniformResourceIdentifier, dp)));
        return pki.crlWith(pki.intermediate.name(), pki.intermediate.keys().getPrivate(), clock.instant().minusSeconds(60), next,
                builder -> { try { builder.addExtension(Extension.issuingDistributionPoint, true,
                    new IssuingDistributionPoint(name, false, false, null, false, false)); }
                    catch (Exception e) { throw new IllegalStateException(e); } }, revoked);
    }
    @Test void anotherReadersPartitionIsFetchedVerifiedAndCachedWithoutChangingTrust() throws Exception {
        trust.crls.add(partition(DP.replace("dp7p12345", "dp6p47981"), clock.instant().plusSeconds(3600)));
        assertThat(new CertificateValidator(trust, clock).validate(leaf.certificate(), List.of(pki.intermediate.certificate())).revocation())
                .isEqualTo(CertificateValidator.RevocationStatus.UNKNOWN);
        byte[] bytes = partition(DP, clock.instant().plusSeconds(3600)).getEncoded();
        AtomicInteger calls = new AtomicInteger();
        var resolver = new YessignCrlResolver(clock, uri -> { calls.incrementAndGet(); return bytes; });
        var validator = new CertificateValidator(trust, clock, resolver);
        for (int i=0;i<2;i++) assertThat(validator.validate(leaf.certificate(), List.of(pki.intermediate.certificate())).revocation())
                .isEqualTo(CertificateValidator.RevocationStatus.GOOD);
        assertThat(calls).hasValue(1);
        clock.advance(Duration.ofMinutes(6));
        assertThat(validator.validate(leaf.certificate(), List.of(pki.intermediate.certificate())).revocation())
                .isEqualTo(CertificateValidator.RevocationStatus.GOOD);
        assertThat(calls).hasValue(2);
    }
    @Test void listedCertificateRemainsRevokedAfterDynamicLookup() throws Exception {
        byte[] bytes = partition(DP, clock.instant().plusSeconds(3600), leaf.certificate()).getEncoded();
        var validator = new CertificateValidator(trust, clock, new YessignCrlResolver(clock, uri -> bytes));
        assertThat(validator.validate(leaf.certificate(), List.of(pki.intermediate.certificate())).revocation())
                .isEqualTo(CertificateValidator.RevocationStatus.REVOKED);
    }
    @Test void expiredWrongIssuerAndWrongPartitionCannotBecomeGood() throws Exception {
        for (byte[] bytes : List.of(partition(DP, clock.instant().minusSeconds(1)).getEncoded(),
                partition(DP.replace("dp7p12345", "dp1p1"), clock.instant().plusSeconds(3600)).getEncoded(),
                pki.crl(pki.root, clock.instant().minusSeconds(60), clock.instant().plusSeconds(3600)).getEncoded())) {
            var validator = new CertificateValidator(trust, clock, new YessignCrlResolver(clock, uri -> bytes));
            assertThat(validator.validate(leaf.certificate(), List.of(pki.intermediate.certificate())).revocation())
                    .isEqualTo(CertificateValidator.RevocationStatus.UNKNOWN);
        }
    }
    @Test void networkFailureIsBoundedAndNeverApproved() {
        AtomicInteger calls = new AtomicInteger();
        var resolver = new YessignCrlResolver(clock, uri -> { calls.incrementAndGet(); throw new java.io.IOException("unavailable"); });
        assertThat(resolver.resolve(leaf.certificate(), pki.intermediate.certificate())).isEmpty();
        assertThat(resolver.resolve(leaf.certificate(), pki.intermediate.certificate())).isEmpty();
        assertThat(calls).hasValue(1);
        clock.advance(Duration.ofSeconds(21));
        assertThat(resolver.resolve(leaf.certificate(), pki.intermediate.certificate())).isEmpty();
        assertThat(calls).hasValue(2);
    }
    @Test void untrustedCertificateNeverTriggersProviderNetworkAccess() {
        trust.anchors.clear();
        AtomicInteger calls = new AtomicInteger();
        var validator = new CertificateValidator(trust, clock, new YessignCrlResolver(clock, uri -> { calls.incrementAndGet(); return null; }));
        assertThatThrownBy(() -> validator.validate(leaf.certificate(), List.of(pki.intermediate.certificate()))).isInstanceOf(RuntimeException.class);
        assertThat(calls).hasValue(0);
    }
    @Test void urlAllowlistRejectsInternalHostsCredentialsPortsAndSearchQueries() {
        assertThat(YessignCrlResolver.allowed(URI.create(DP))).isTrue();
        for (String invalid : List.of(DP.replace("ds.yessign.or.kr", "127.0.0.1"), DP.replace("ds.yessign.or.kr", "metadata.google.internal"),
                DP.replace("ds.yessign.or.kr", "ds.yessign.or.kr.evil.test"), DP.replace(":389/", ":80/"),
                DP.replace("ldap://", "http://"), DP.replace("ldap://", "ldap://user:pass@"), DP + "?sub?(objectClass=*)",
                DP.replace("ou=dp7p12345", "ou=admins"), DP + "#fragment")) {
            assertThat(YessignCrlResolver.allowed(URI.create(invalid))).as(invalid).isFalse();
        }
    }
}
