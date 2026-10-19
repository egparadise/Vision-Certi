package com.inviz.visioncerti.doctor.credential;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Minimal OpenBao HTTP API client. Tokens go only into the {@code X-Vault-Token} header and are never logged. */
final class OpenBaoClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http;
    private final String base;
    private final String namespace;
    private final Duration timeout;

    OpenBaoClient(OpenBaoProperties properties) {
        String addr = properties.addr();
        this.base = addr.endsWith("/") ? addr.substring(0, addr.length() - 1) : addr;
        this.namespace = properties.namespace() == null || properties.namespace().isBlank() ? null
                : properties.namespace();
        this.timeout = properties.timeout();
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER);
        if (properties.caCertFile() != null && !properties.caCertFile().isBlank()) {
            builder.sslContext(trusting(Path.of(properties.caCertFile())));
        }
        this.http = builder.build();
    }

    /** Returns the parsed body, or null for HTTP 404. */
    Map<String, Object> get(String path, String token) {
        return send(request(path, token).GET().build(), "GET", true);
    }

    Map<String, Object> post(String path, String token, Map<String, Object> body) {
        byte[] json;
        try {
            json = JSON.writeValueAsBytes(body);
        } catch (JacksonException e) {
            throw new CredentialException(CredentialException.Kind.UNAVAILABLE, "Could not encode OpenBao request", e);
        }
        return send(request(path, token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json)).build(), "POST", false);
    }

    private HttpRequest.Builder request(String path, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + "/v1/" + path)).timeout(timeout);
        if (token != null) {
            builder.header("X-Vault-Token", token);
        }
        if (namespace != null) {
            builder.header("X-Vault-Namespace", namespace);
        }
        return builder;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> send(HttpRequest request, String method, boolean allowNotFound) {
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new CredentialException(CredentialException.Kind.UNAVAILABLE,
                    "OpenBao is unreachable (" + method + " " + request.uri().getPath() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CredentialException(CredentialException.Kind.UNAVAILABLE, "Interrupted while calling OpenBao", e);
        }
        int status = response.statusCode();
        if (status == 404 && allowNotFound) {
            return null;
        }
        Map<String, Object> body = Map.of();
        if (response.body().length > 0) {
            try {
                body = JSON.readValue(response.body(), Map.class);
            } catch (JacksonException e) {
                throw new CredentialException(CredentialException.Kind.UNAVAILABLE,
                        "OpenBao returned a non-JSON body for " + method + " " + request.uri().getPath());
            }
        }
        if (status < 200 || status >= 300) {
            String detail = "";
            if (body.get("errors") instanceof List<?> errors && !errors.isEmpty()) {
                String first = String.valueOf(errors.getFirst());
                detail = ": " + (first.length() > 200 ? first.substring(0, 200) : first);
            }
            throw new CredentialException(CredentialException.Kind.UNAVAILABLE,
                    "OpenBao " + method + " " + request.uri().getPath() + " returned HTTP " + status + detail);
        }
        return body;
    }

    private static SSLContext trusting(Path pemBundle) {
        try (InputStream in = Files.newInputStream(pemBundle)) {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int i = 0;
            for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                store.setCertificateEntry("openbao-ca-" + i++, certificate);
            }
            if (i == 0) {
                throw new IllegalStateException("VISIONCERTI_OPENBAO_CA_CERT_FILE contains no certificates");
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, tmf.getTrustManagers(), null);
            return context;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Cannot load VISIONCERTI_OPENBAO_CA_CERT_FILE", e);
        }
    }
}
