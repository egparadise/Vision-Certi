package com.inviz.visioncerti.doctor.credential;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * OpenBao connection settings (environment variables {@code VISIONCERTI_OPENBAO_*}). Tokens are never printed.
 * {@link #problem()} explains, without secrets, why the settings cannot be used.
 */
public record OpenBaoProperties(
        String addr,
        String namespace,
        String provisioningToken,
        String signingToken,
        String transitMount,
        String pkiMount,
        String pkiRole,
        String keyPrefix,
        String certTtl,
        String caCertFile,
        Duration timeout) {

    private static final Pattern MOUNT = Pattern.compile("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern IPV4_LOOPBACK = Pattern.compile("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    /** Null when usable; otherwise a reason that names variables but never values. */
    public String problem() {
        if (blank(addr)) {
            return "VISIONCERTI_OPENBAO_ADDR is not set";
        }
        URI uri;
        try {
            uri = new URI(addr);
        } catch (URISyntaxException e) {
            return "VISIONCERTI_OPENBAO_ADDR is not a valid URI";
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            return "VISIONCERTI_OPENBAO_ADDR must be scheme://host[:port] without credentials, query or fragment";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("http")) {
            if (!isLoopback(uri.getHost())) {
                return "plain HTTP to OpenBao is only allowed for a loopback address; use https";
            }
        } else if (!scheme.equals("https")) {
            return "VISIONCERTI_OPENBAO_ADDR must use https (or http to loopback)";
        }
        if (blank(provisioningToken) || blank(signingToken)) {
            return "VISIONCERTI_OPENBAO_PROVISIONING_TOKEN and VISIONCERTI_OPENBAO_SIGNING_TOKEN are required";
        }
        if (provisioningToken.equals(signingToken)) {
            return "provisioning and signing tokens must be different credentials";
        }
        if (!MOUNT.matcher(nz(transitMount)).matches() || !MOUNT.matcher(nz(pkiMount)).matches()) {
            return "VISIONCERTI_OPENBAO_TRANSIT_MOUNT / PKI_MOUNT contain invalid characters";
        }
        if (!NAME.matcher(nz(pkiRole)).matches() || !NAME.matcher(nz(keyPrefix)).matches()) {
            return "VISIONCERTI_OPENBAO_PKI_ROLE / KEY_PREFIX contain invalid characters";
        }
        if (blank(certTtl) || timeout == null || timeout.isNegative() || timeout.isZero()) {
            return "VISIONCERTI_OPENBAO_CERT_TTL and VISIONCERTI_OPENBAO_TIMEOUT must be set";
        }
        return null;
    }

    static boolean isLoopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("[::1]") || h.equals("::1") || IPV4_LOOPBACK.matcher(h).matches();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    @Override
    public String toString() {
        return "OpenBaoProperties[addr=" + addr + ", namespace=" + namespace + ", transitMount=" + transitMount
                + ", pkiMount=" + pkiMount + ", pkiRole=" + pkiRole + ", keyPrefix=" + keyPrefix
                + ", tokens=<redacted>]";
    }
}
