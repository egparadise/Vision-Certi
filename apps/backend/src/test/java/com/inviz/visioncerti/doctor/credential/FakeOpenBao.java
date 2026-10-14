package com.inviz.visioncerti.doctor.credential;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loopback stand-in for the OpenBao 2.x Transit and PKI endpoints this backend calls. It enforces the token
 * split (provisioning vs signing), signs with real ECDSA P-256 keys, verifies CSRs and issues real X.509
 * certificates from a test CA, so the adapter is exercised against the documented request/response shapes.
 */
public final class FakeOpenBao implements AutoCloseable {

    static final String PROVISIONING_TOKEN = "prov-token-test";
    static final String SIGNING_TOKEN = "sign-token-test";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final boolean tls;
    private java.nio.file.Path tlsCaFile;
    private final KeyPair caKey = ec();
    private final X509Certificate caCert;
    private final AtomicLong serials = new AtomicLong(0x1000);

    final Map<String, KeyPair> keys = new HashMap<>();
    final Map<String, Map<String, Object>> keyMeta = new HashMap<>();
    public final Map<String, Long> revoked = new HashMap<>();
    public final List<Map<String, Object>> signBodies = new ArrayList<>();
    final List<String> denied = new ArrayList<>();
    int keyCreates;
    int certificatesIssued;
    /** When set, PKI certifies this key instead of the CSR key (tamper simulation). */
    KeyPair certifyInstead;
    /** When set, Transit signs with this key instead of the named key. */
    KeyPair signInstead;
    /** When set, Transit reports this version prefix. */
    Integer reportVersion;
    /** When set, PKI reports this certificate as issuing_ca (impostor CA simulation). */
    X509Certificate reportCa;
    boolean omitIssuingCa;

    public FakeOpenBao() throws Exception {
        this(false);
    }

    /**
     * With {@code tls}, the fake listens with HTTPS: a server certificate for IP 127.0.0.1 issued by a throwaway TLS CA
     * whose PEM is {@link #tlsCaFile()} (what VISIONCERTI_OPENBAO_CA_CERT_FILE points to in prod-profile tests).
     */
    public FakeOpenBao(boolean tls) throws Exception {
        caCert = selfSigned(caKey);
        this.tls = tls;
        InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        if (tls) {
            com.sun.net.httpserver.HttpsServer https = com.sun.net.httpserver.HttpsServer.create(address, 0);
            https.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(tlsContext()));
            server = https;
        } else {
            server = HttpServer.create(address, 0);
        }
        server.createContext("/v1/", this::handle);
        server.start();
    }

    public String addr() {
        return (tls ? "https" : "http") + "://127.0.0.1:" + server.getAddress().getPort();
    }

    public java.nio.file.Path tlsCaFile() {
        return tlsCaFile;
    }

    public static String provisioningToken() {
        return PROVISIONING_TOKEN;
    }

    public static String signingToken() {
        return SIGNING_TOKEN;
    }

    public synchronized int keyCreates() {
        return keyCreates;
    }

    public synchronized int certificatesIssued() {
        return certificatesIssued;
    }

    private javax.net.ssl.SSLContext tlsContext() throws Exception {
        KeyPair tlsCaKey = ec();
        Instant issued = Instant.now();
        X500Name caName = new X500Name("CN=Fake OpenBao TLS CA");
        JcaX509v3CertificateBuilder caBuilder = new JcaX509v3CertificateBuilder(caName, BigInteger.ONE,
                Date.from(issued.minus(1, ChronoUnit.DAYS)), Date.from(issued.plus(30, ChronoUnit.DAYS)), caName,
                tlsCaKey.getPublic());
        caBuilder.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints, true,
                new org.bouncycastle.asn1.x509.BasicConstraints(true));
        caBuilder.addExtension(org.bouncycastle.asn1.x509.Extension.keyUsage, true,
                new org.bouncycastle.asn1.x509.KeyUsage(org.bouncycastle.asn1.x509.KeyUsage.keyCertSign
                        | org.bouncycastle.asn1.x509.KeyUsage.cRLSign));
        X509Certificate tlsCa = new JcaX509CertificateConverter().getCertificate(
                caBuilder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(tlsCaKey.getPrivate())));
        KeyPair serverKey = ec();
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(tlsCa, BigInteger.TWO,
                Date.from(now.minus(1, ChronoUnit.DAYS)), Date.from(now.plus(30, ChronoUnit.DAYS)),
                new javax.security.auth.x500.X500Principal("CN=127.0.0.1"), serverKey.getPublic());
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.subjectAlternativeName, false,
                new org.bouncycastle.asn1.x509.GeneralNames(new org.bouncycastle.asn1.x509.GeneralName(
                        org.bouncycastle.asn1.x509.GeneralName.iPAddress, "127.0.0.1")));
        X509Certificate serverCert = new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(tlsCaKey.getPrivate())));
        java.security.KeyStore store = java.security.KeyStore.getInstance("PKCS12");
        store.load(null, null);
        char[] password = "fake-openbao-tls".toCharArray();
        store.setKeyEntry("server", serverKey.getPrivate(), password,
                new java.security.cert.Certificate[] {serverCert, tlsCa});
        javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance(
                javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, password);
        javax.net.ssl.SSLContext context = javax.net.ssl.SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, null);
        tlsCaFile = java.nio.file.Files.createTempFile("fake-openbao-tls-ca", ".pem");
        java.nio.file.Files.writeString(tlsCaFile, pem("CERTIFICATE", tlsCa.getEncoded()));
        tlsCaFile.toFile().deleteOnExit();
        return context;
    }

    public OpenBaoProperties properties() {
        return new OpenBaoProperties(addr(), "", PROVISIONING_TOKEN, SIGNING_TOKEN, "transit", "pki",
                "vision-certi-doctor", "vc-doctor", "720h", "", java.time.Duration.ofSeconds(5));
    }

    void preloadKey(String name, String type, boolean exportable) {
        keys.put(name, ec());
        keyMeta.put(name, Map.of("type", type, "exportable", exportable));
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private synchronized void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath().substring("/v1/".length());
            String method = ex.getRequestMethod();
            String token = ex.getRequestHeaders().getFirst("X-Vault-Token");
            Map<String, Object> body = method.equals("POST") ? readJson(ex) : Map.of();

            if (path.startsWith("transit/keys/")) {
                if (!require(ex, token, PROVISIONING_TOKEN, path)) {
                    return;
                }
                String name = path.substring("transit/keys/".length());
                if (method.equals("GET")) {
                    KeyPair pair = keys.get(name);
                    if (pair == null) {
                        send(ex, 404, Map.of("errors", List.of()));
                        return;
                    }
                    Map<String, Object> meta = keyMeta.getOrDefault(name, Map.of());
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("type", meta.getOrDefault("type", "ecdsa-p256"));
                    data.put("exportable", meta.getOrDefault("exportable", false));
                    data.put("allow_plaintext_backup", false);
                    data.put("latest_version", 1);
                    data.put("keys", Map.of("1", Map.of("public_key", pem("PUBLIC KEY", pair.getPublic().getEncoded()),
                            "name", "P-256")));
                    send(ex, 200, Map.of("data", data));
                } else {
                    if (!"ecdsa-p256".equals(body.get("type")) || !Boolean.FALSE.equals(body.get("exportable"))) {
                        send(ex, 400, Map.of("errors", List.of("unexpected key parameters")));
                        return;
                    }
                    keyCreates++;
                    keys.putIfAbsent(name, ec());
                    keyMeta.putIfAbsent(name, Map.of("type", "ecdsa-p256", "exportable", false));
                    send(ex, 204, null);
                }
                return;
            }
            if (path.startsWith("transit/sign/")) {
                if (!require(ex, token, SIGNING_TOKEN, path)) {
                    return;
                }
                String name = path.substring("transit/sign/".length());
                signBodies.add(body);
                KeyPair pair = signInstead != null ? signInstead : keys.get(name);
                if (pair == null || !Boolean.TRUE.equals(body.get("prehashed"))
                        || !"sha2-256".equals(body.get("hash_algorithm"))
                        || !"asn1".equals(body.get("marshaling_algorithm"))
                        || !Integer.valueOf(1).equals(body.get("key_version"))) {
                    send(ex, 400, Map.of("errors", List.of("bad sign request")));
                    return;
                }
                byte[] digest = Base64.getDecoder().decode((String) body.get("input"));
                Signature signer = Signature.getInstance("NONEwithECDSA");
                signer.initSign(pair.getPrivate());
                signer.update(digest);
                int version = reportVersion == null ? 1 : reportVersion;
                send(ex, 200, Map.of("data", Map.of("signature",
                        "vault:v" + version + ":" + Base64.getEncoder().encodeToString(signer.sign()),
                        "key_version", version)));
                return;
            }
            if (path.startsWith("pki/sign/")) {
                if (!require(ex, token, PROVISIONING_TOKEN, path)) {
                    return;
                }
                PKCS10CertificationRequest csr;
                try (PEMParser parser = new PEMParser(new StringReader((String) body.get("csr")))) {
                    csr = (PKCS10CertificationRequest) parser.readObject();
                }
                JcaPKCS10CertificationRequest jcaCsr = new JcaPKCS10CertificationRequest(csr);
                if (!csr.isSignatureValid(new JcaContentVerifierProviderBuilder().build(jcaCsr.getPublicKey()))) {
                    send(ex, 400, Map.of("errors", List.of("csr signature invalid")));
                    return;
                }
                BigInteger serial = BigInteger.valueOf(serials.incrementAndGet());
                Instant now = Instant.now();
                X509Certificate cert = new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(
                        new X500Name(caCert.getSubjectX500Principal().getName()), serial,
                        Date.from(now.minus(1, ChronoUnit.MINUTES)), Date.from(now.plus(30, ChronoUnit.DAYS)),
                        new X500Name("CN=" + body.get("common_name")),
                        certifyInstead != null ? certifyInstead.getPublic() : jcaCsr.getPublicKey())
                        .build(new JcaContentSignerBuilder("SHA256withECDSA").build(caKey.getPrivate())));
                certificatesIssued++;
                String serialText = colonHex(serial);
                revoked.put(serialText, 0L);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("certificate", pem("CERTIFICATE", cert.getEncoded()));
                if (!omitIssuingCa) {
                    data.put("issuing_ca", pem("CERTIFICATE", (reportCa != null ? reportCa : caCert).getEncoded()));
                    data.put("ca_chain", List.of(pem("CERTIFICATE", (reportCa != null ? reportCa : caCert).getEncoded())));
                }
                data.put("serial_number", serialText);
                send(ex, 200, Map.of("data", data));
                return;
            }
            if (path.startsWith("pki/cert/") && method.equals("GET")) {
                Long time = revoked.get(path.substring("pki/cert/".length()));
                if (time == null) {
                    send(ex, 404, Map.of("errors", List.of()));
                    return;
                }
                send(ex, 200, Map.of("data", Map.of("revocation_time", time)));
                return;
            }
            if (path.equals("pki/revoke")) {
                if (!require(ex, token, PROVISIONING_TOKEN, path)) {
                    return;
                }
                revoked.put((String) body.get("serial_number"), Instant.now().getEpochSecond());
                send(ex, 200, Map.of("data", Map.of("revocation_time", Instant.now().getEpochSecond())));
                return;
            }
            send(ex, 404, Map.of("errors", List.of("no handler")));
        } catch (Exception e) {
            send(ex, 500, Map.of("errors", List.of(String.valueOf(e))));
        }
    }

    private boolean require(HttpExchange ex, String token, String expected, String path) throws IOException {
        if (!expected.equals(token)) {
            denied.add(path);
            send(ex, 403, Map.of("errors", List.of("permission denied")));
            return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(HttpExchange ex) throws IOException {
        byte[] bytes = ex.getRequestBody().readAllBytes();
        return bytes.length == 0 ? Map.of() : JSON.readValue(bytes, Map.class);
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    static String colonHex(BigInteger serial) {
        String hex = serial.toString(16);
        if (hex.length() % 2 == 1) {
            hex = "0" + hex;
        }
        return String.join(":", hex.split("(?<=\\G..)"));
    }

    static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    static KeyPair ec() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Same subject DN as the real test CA but a different key. */
    static X509Certificate impostorCa() throws Exception {
        return selfSigned(ec());
    }

    private static X509Certificate selfSigned(KeyPair key) throws Exception {
        Instant now = Instant.now();
        X500Name name = new X500Name("CN=Fake OpenBao Test CA");
        return new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(name,
                BigInteger.ONE, Date.from(now.minus(1, ChronoUnit.DAYS)), Date.from(now.plus(365, ChronoUnit.DAYS)),
                name, key.getPublic()).build(new JcaContentSignerBuilder("SHA256withECDSA").build(key.getPrivate())));
    }
}
