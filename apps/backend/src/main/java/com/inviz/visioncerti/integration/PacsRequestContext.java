package com.inviz.visioncerti.integration;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Request observations only: forwarded IPs and user agents are never authorization inputs. */
public record PacsRequestContext(String peerIp, String forwardedFor, String userAgent, String origin) {
    public static final PacsRequestContext EMPTY = new PacsRequestContext("", "", "", "");

    public static PacsRequestContext from(HttpServletRequest request) {
        return new PacsRequestContext(clean(request.getRemoteAddr(), 64),
                ipChain(request.getHeader("X-Forwarded-For")), clean(request.getHeader("User-Agent"), 256),
                originOnly(request.getHeader("Origin")));
    }

    public Map<String, String> details() {
        Map<String, String> result = new LinkedHashMap<>();
        put(result, "peerIp", clean(peerIp, 64));
        put(result, "forwardedFor", ipChain(forwardedFor));
        put(result, "userAgent", clean(userAgent, 256));
        put(result, "origin", originOnly(origin));
        if (forwardedFor != null && !forwardedFor.isBlank()) result.put("forwardedIpTrust", "UNVERIFIED");
        return result;
    }

    private static String ipChain(String value) {
        String clean = clean(value, 512);
        return clean.matches("[0-9a-fA-F:., \\s]*") ? clean : "";
    }

    private static String originOnly(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null) return "";
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        } catch (IllegalArgumentException e) { return ""; }
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        return value.substring(0, Math.min(max, value.length())).replaceAll("[\\p{Cntrl}]", " ").strip();
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (!value.isBlank()) map.put(key, value);
    }
}
