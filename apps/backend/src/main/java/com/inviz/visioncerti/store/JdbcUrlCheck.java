package com.inviz.visioncerti.store;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Structural check of {@code VISIONCERTI_DB_URL}. The URL is parsed (authority and query split, every query key and
 * value percent-decoded) instead of substring-matched, so encodings, duplicates and look-alike values cannot smuggle a
 * weaker setting past the check:
 *
 * <ul>
 *   <li>no userinfo in the authority and no {@code user}/{@code password} query parameter (credentials come only from
 *       {@code VISIONCERTI_DB_USERNAME} and the password file);</li>
 *   <li>every query key appears once (case-insensitively), and names are not percent-encoded (the driver decodes
 *       values but not names);</li>
 *   <li>no parameter that can replace connection settings or disable certificate checks ({@code service},
 *       {@code sslfactory}, {@code sslhostnameverifier}, ...);</li>
 *   <li>when TLS is required and any host is not loopback: exactly {@code sslmode=verify-full}.</li>
 * </ul>
 *
 * Messages name the variable and the rule, never the URL or its values.
 */
final class JdbcUrlCheck {

    private static final String PREFIX = "jdbc:postgresql://";
    private static final Set<String> FORBIDDEN_KEYS = Set.of("user", "password", "service", "sslfactory",
            "sslfactoryarg", "sslhostnameverifier", "sslpasswordcallback", "authenticationpluginclassname",
            "jaasapplicationname", "options");
    private static final List<String> LOOPBACK = List.of("localhost", "127.0.0.1", "[::1]");

    private JdbcUrlCheck() {
    }

    static String problem(String url, boolean requireTls) {
        if (url == null || !url.startsWith(PREFIX)) {
            return "VISIONCERTI_DB_URL must be a jdbc:postgresql:// URL";
        }
        String rest = url.substring(PREFIX.length());
        if (rest.indexOf('#') >= 0) {
            return "VISIONCERTI_DB_URL must not contain a fragment";
        }
        int queryStart = rest.indexOf('?');
        String beforeQuery = queryStart < 0 ? rest : rest.substring(0, queryStart);
        String query = queryStart < 0 ? "" : rest.substring(queryStart + 1);
        int slash = beforeQuery.indexOf('/');
        String authority = slash < 0 ? beforeQuery : beforeQuery.substring(0, slash);
        if (authority.isEmpty()) {
            return "VISIONCERTI_DB_URL must name a host";
        }
        if (authority.indexOf('@') >= 0 || authority.indexOf('%') >= 0) {
            return "VISIONCERTI_DB_URL must not contain user information or encoded characters in the host part";
        }

        Map<String, String> params = new HashMap<>();
        if (!query.isEmpty()) {
            for (String pair : query.split("&", -1)) {
                if (pair.isEmpty()) {
                    return "VISIONCERTI_DB_URL has an empty query parameter";
                }
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                if (key.indexOf('%') >= 0 || key.indexOf('+') >= 0) {
                    // The driver does not decode parameter names: an encoded name is a different, ignored key.
                    return "VISIONCERTI_DB_URL query parameter names must not be encoded";
                }
                String value;
                try {
                    value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    return "VISIONCERTI_DB_URL has a malformed percent-encoding";
                }
                String normalized = key.strip().toLowerCase(Locale.ROOT);
                if (normalized.isEmpty()) {
                    return "VISIONCERTI_DB_URL has an empty query parameter";
                }
                if (params.containsKey(normalized)) {
                    return "VISIONCERTI_DB_URL repeats a query parameter";
                }
                if (FORBIDDEN_KEYS.contains(normalized)) {
                    return "VISIONCERTI_DB_URL must not set user, password, service, options or SSL factory/verifier "
                            + "parameters";
                }
                if (normalized.equals("sslmode") && !key.equals("sslmode")) {
                    // The driver's property names are case-sensitive: "SSLMODE" would be silently ignored.
                    return "VISIONCERTI_DB_URL must spell the parameter exactly as sslmode";
                }
                params.put(normalized, value);
            }
        }
        if (requireTls && !allLoopback(authority) && !"verify-full".equals(params.get("sslmode"))) {
            return "VISIONCERTI_DB_URL must use exactly sslmode=verify-full for a non-loopback database";
        }
        return null;
    }

    /** True when every host of a (possibly multi-host) authority is a loopback address. */
    private static boolean allLoopback(String authority) {
        for (String hostPort : authority.split(",", -1)) {
            String host = hostPort;
            if (host.startsWith("[")) {
                int end = host.indexOf(']');
                host = end < 0 ? host : host.substring(0, end + 1);
            } else {
                int colon = host.indexOf(':');
                host = colon < 0 ? host : host.substring(0, colon);
            }
            if (!LOOPBACK.contains(host.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }
}
