package com.inviz.visioncerti.doctor.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inviz.visioncerti.common.Hashing;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HTTP contract test of the OpenBao Transit/PKI adapter against a loopback fake with real ECDSA and X.509. */
class OpenBaoCredentialProviderTest {

    private FakeOpenBao bao;
    private OpenBaoCredentialProvider provider;
    private final String doctorId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() throws Exception {
        bao = new FakeOpenBao();
        provider = new OpenBaoCredentialProvider(bao.properties(), Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        bao.close();
    }

    private static X509Certificate certificate(DoctorCredential credential) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(
                new ByteArrayInputStream(credential.certificatePem().getBytes(StandardCharsets.US_ASCII)));
    }

    private static CredentialException.Kind kind(Throwable t) {
        return ((CredentialException) t).kind();
    }

    @Test
    void provisionCreatesNonExportableKeyAndCertifiesThatSamePublicKey() throws Exception {
        DoctorCredential credential = provider.provision(doctorId);

        assertThat(credential.backend()).isEqualTo("OPENBAO_TRANSIT");
        assertThat(credential.keyName()).isEqualTo("vc-doctor-" + doctorId);
        assertThat(credential.keyVersion()).isEqualTo(1);
        assertThat(credential.certificateKind()).isEqualTo("X509");
        assertThat(bao.keyCreates).isEqualTo(1);
        // The CSR proof-of-possession was signed through Transit with the signing token.
        assertThat(bao.signBodies).hasSize(1);
        assertThat(bao.denied).isEmpty();

        PublicKey transitKey = bao.keys.get(credential.keyName()).getPublic();
        X509Certificate cert = certificate(credential);
        assertThat(cert.getPublicKey().getEncoded()).isEqualTo(transitKey.getEncoded());
        assertThat(credential.publicKeyDer()).isEqualTo(transitKey.getEncoded());
        assertThat(credential.publicKeySha256()).isEqualTo(Hashing.sha256Hex(transitKey.getEncoded()));
        assertThat(cert.getSerialNumber().toString(16)).isEqualTo(credential.serialNumber().replace(":", "")
                .replaceFirst("^0+", ""));
        assertThat(credential.subject()).contains("vc-doctor-" + doctorId);
    }

    @Test
    void retryReusesDeterministicKey() {
        DoctorCredential first = provider.provision(doctorId);
        DoctorCredential second = provider.provision(doctorId);
        assertThat(bao.keyCreates).isEqualTo(1);
        assertThat(second.keyName()).isEqualTo(first.keyName());
        assertThat(second.publicKeySpki()).isEqualTo(first.publicKeySpki());
        assertThat(bao.certificatesIssued).isEqualTo(2);
    }

    @Test
    void signSendsExactPrehashedDigestWithPinnedVersionAndVerifiesWithCertificateKey() throws Exception {
        DoctorCredential credential = provider.provision(doctorId);
        byte[] document = "%PDF-1.7\nreport body\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        byte[] digest = Hashing.sha256(document);

        byte[] der = provider.signDigest(credential, digest);

        Map<String, Object> request = bao.signBodies.getLast();
        assertThat(request.get("input")).isEqualTo(Base64.getEncoder().encodeToString(digest));
        assertThat(request.get("prehashed")).isEqualTo(true);
        assertThat(request.get("hash_algorithm")).isEqualTo("sha2-256");
        assertThat(request.get("marshaling_algorithm")).isEqualTo("asn1");
        assertThat(request.get("key_version")).isEqualTo(1);

        // A Transit prehashed signature is an ordinary ECDSA-SHA256 signature over the document bytes.
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(certificate(credential).getPublicKey());
        verifier.update(document);
        assertThat(verifier.verify(der)).isTrue();

        verifier.initVerify(certificate(credential).getPublicKey());
        verifier.update("%PDF-1.7\nreport bodY\n%%EOF".getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(der)).isFalse();
    }

    @Test
    void certificateForAnotherKeyIsRejected() {
        bao.certifyInstead = FakeOpenBao.ec();
        assertThatThrownBy(() -> provider.provision(doctorId))
                .satisfies(t -> assertThat(kind(t)).isEqualTo(CredentialException.Kind.KEY_MISMATCH));
    }

    @Test
    void certificateMustChainToTheReturnedIssuingCa() throws Exception {
        bao.reportCa = FakeOpenBao.impostorCa();
        assertThatThrownBy(() -> provider.provision(doctorId))
                .satisfies(t -> {
                    assertThat(kind(t)).isEqualTo(CredentialException.Kind.UNAVAILABLE);
                    assertThat(t.getMessage()).contains("issuing CA");
                });
        bao.reportCa = null;
        bao.omitIssuingCa = true;
        assertThatThrownBy(() -> provider.provision(doctorId))
                .satisfies(t -> assertThat(t.getMessage()).contains("issuing_ca"));
        bao.omitIssuingCa = false;
        assertThat(provider.provision(doctorId).certificateKind()).isEqualTo("X509");
    }

    @Test
    void signatureFromAnotherKeyOrVersionIsRejected() {
        DoctorCredential credential = provider.provision(doctorId);
        byte[] digest = Hashing.sha256(new byte[] {1, 2, 3});

        bao.signInstead = FakeOpenBao.ec();
        assertThatThrownBy(() -> provider.signDigest(credential, digest))
                .satisfies(t -> assertThat(kind(t)).isEqualTo(CredentialException.Kind.KEY_MISMATCH));

        bao.signInstead = null;
        bao.reportVersion = 2;
        assertThatThrownBy(() -> provider.signDigest(credential, digest))
                .satisfies(t -> assertThat(kind(t)).isEqualTo(CredentialException.Kind.UNAVAILABLE));
    }

    @Test
    void existingKeyWithWrongTypeOrExportableIsRejected() {
        bao.preloadKey("vc-doctor-" + doctorId, "rsa-2048", false);
        assertThatThrownBy(() -> provider.provision(doctorId))
                .satisfies(t -> assertThat(kind(t)).isEqualTo(CredentialException.Kind.KEY_POLICY_VIOLATION));

        String other = UUID.randomUUID().toString();
        bao.preloadKey("vc-doctor-" + other, "ecdsa-p256", true);
        assertThatThrownBy(() -> provider.provision(other))
                .satisfies(t -> assertThat(kind(t)).isEqualTo(CredentialException.Kind.KEY_POLICY_VIOLATION));
        assertThat(bao.certificatesIssued).isZero();
    }

    @Test
    void revocationIsCheckedAgainstPki() {
        DoctorCredential credential = provider.provision(doctorId);
        provider.assertNotRevoked(credential);
        provider.revoke(credential);
        assertThatThrownBy(() -> provider.assertNotRevoked(credential))
                .satisfies(t -> assertThat(kind(t)).isEqualTo(CredentialException.Kind.CERTIFICATE_NOT_VALID));
    }

    @Test
    void tokensAreSeparated() {
        OpenBaoProperties p = bao.properties();
        OpenBaoProperties swapped = new OpenBaoProperties(p.addr(), "", p.signingToken() + "-x", p.signingToken(),
                "transit", "pki", "vision-certi-doctor", "vc-doctor", "720h", "", Duration.ofSeconds(5));
        assertThatThrownBy(() -> new OpenBaoCredentialProvider(swapped, Clock.systemUTC()).provision(doctorId))
                .satisfies(t -> assertThat(kind(t)).isEqualTo(CredentialException.Kind.UNAVAILABLE));
        assertThat(bao.denied).isNotEmpty();

        // A provisioning token cannot sign.
        DoctorCredential credential = provider.provision(doctorId);
        OpenBaoProperties provAsSigner = new OpenBaoProperties(p.addr(), "", p.provisioningToken(),
                p.provisioningToken() + "-not-signing", "transit", "pki", "vision-certi-doctor", "vc-doctor", "720h",
                "", Duration.ofSeconds(5));
        assertThatThrownBy(() -> new OpenBaoCredentialProvider(provAsSigner, Clock.systemUTC())
                .signDigest(credential, Hashing.sha256(new byte[] {9})))
                .satisfies(t -> assertThat(kind(t)).isEqualTo(CredentialException.Kind.UNAVAILABLE));
    }

    @Test
    void unreachableBackendFailsClosed() {
        OpenBaoProperties p = bao.properties();
        bao.close();
        OpenBaoCredentialProvider dead = new OpenBaoCredentialProvider(p, Clock.systemUTC());
        assertThatThrownBy(() -> dead.provision(doctorId))
                .satisfies(t -> assertThat(kind(t)).isEqualTo(CredentialException.Kind.UNAVAILABLE));
    }

    @Test
    void propertiesRequireTlsForRemoteHostsAndDistinctTokens() {
        assertThat(props("http://127.0.0.1:8200", "a", "b").problem()).isNull();
        assertThat(props("http://localhost:8200", "a", "b").problem()).isNull();
        assertThat(props("http://[::1]:8200", "a", "b").problem()).isNull();
        assertThat(props("https://bao.example.org", "a", "b").problem()).isNull();
        assertThat(props("http://bao.example.org:8200", "a", "b").problem()).contains("loopback");
        assertThat(props("http://10.0.0.5:8200", "a", "b").problem()).contains("loopback");
        assertThat(props("ftp://127.0.0.1", "a", "b").problem()).contains("https");
        assertThat(props("https://user:pw@bao.example.org", "a", "b").problem()).contains("without credentials");
        assertThat(props("", "a", "b").problem()).contains("VISIONCERTI_OPENBAO_ADDR");
        assertThat(props("https://bao.example.org", "same", "same").problem()).contains("different");
        assertThat(props("https://bao.example.org", "", "b").problem()).contains("required");
        assertThat(props("https://bao.example.org", "secret-a", "secret-b").toString())
                .doesNotContain("secret-a").doesNotContain("secret-b");
        assertThatThrownBy(() -> new OpenBaoCredentialProvider(props("http://bao.example.org", "a", "b"),
                Clock.systemUTC())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicKeyParsingRejectsNonP256() throws Exception {
        java.security.KeyPairGenerator rsa = java.security.KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        byte[] spki = rsa.generateKeyPair().getPublic().getEncoded();
        assertThatThrownBy(() -> OpenBaoCredentialProvider.parsePublicKey(spki))
                .isInstanceOf(CredentialException.class);
        PublicKey ec = KeyFactory.getInstance("EC").generatePublic(
                new X509EncodedKeySpec(FakeOpenBao.ec().getPublic().getEncoded()));
        assertThat(OpenBaoCredentialProvider.parsePublicKey(ec.getEncoded())).isNotNull();
    }

    private static OpenBaoProperties props(String addr, String prov, String sign) {
        return new OpenBaoProperties(addr, "", prov, sign, "transit", "pki", "role", "vc-doctor", "720h", "",
                Duration.ofSeconds(5));
    }
}
