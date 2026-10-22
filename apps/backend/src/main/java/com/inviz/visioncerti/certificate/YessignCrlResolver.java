package com.inviz.visioncerti.certificate;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import javax.naming.Context;
import javax.naming.directory.InitialDirContext;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.slf4j.LoggerFactory;

/** Issuer CRLs only, after PKIX validation. No arbitrary URL, LDAP query, referral, or object lookup. */
public final class YessignCrlResolver {
    private static final String HOST = "ds.yessign.or.kr";
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ENTRIES = 64;
    private final Clock clock;
    private final Fetcher fetcher;
    private final Object[] locks = new Object[16];
    private final LinkedHashMap<String, Entry> cache = new LinkedHashMap<>(16, .75f, true);
    record Entry(X509CRL crl, Instant until) { }
    @FunctionalInterface interface Fetcher { byte[] fetch(URI uri) throws Exception; }

    public YessignCrlResolver(Clock clock) { this(clock, YessignCrlResolver::download); }
    YessignCrlResolver(Clock clock, Fetcher fetcher) {
        this.clock = clock;
        this.fetcher = fetcher;
        java.util.Arrays.setAll(locks, i -> new Object());
    }

    static boolean allowed(URI uri) {
        String query = uri.getRawQuery();
        return "ldap".equalsIgnoreCase(uri.getScheme()) && HOST.equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 389) && uri.getUserInfo() == null && uri.getFragment() == null
                && uri.toString().length() <= 512 && uri.getPath() != null
                && uri.getPath().matches("(?i)/ou=dp[a-z0-9]{1,24},ou=AccreditedCA,o=yessign,c=kr")
                && query != null && query.matches("(?i)certificateRevocationList(;binary)?");
    }

    /** Limited to the provider-approved CRL locations; safe to include in administrative diagnostics. */
    public static List<URI> distributionPoints(X509Certificate certificate) {
        List<URI> result = new ArrayList<>();
        try {
            byte[] encoded = certificate.getExtensionValue(Extension.cRLDistributionPoints.getId());
            if (encoded == null) return List.of();
            var points = CRLDistPoint.getInstance(ASN1OctetString.getInstance(encoded).getOctets());
            for (var point : points.getDistributionPoints()) {
                var name = point.getDistributionPoint();
                if (point.getCRLIssuer() != null || point.getReasons() != null || name == null
                        || name.getType() != DistributionPointName.FULL_NAME) continue;
                for (var general : GeneralNames.getInstance(name.getName()).getNames()) {
                    if (general.getTagNo() != GeneralName.uniformResourceIdentifier) continue;
                    URI uri = URI.create(general.getName().toString());
                    if (allowed(uri) && !result.contains(uri)) result.add(uri);
                    if (result.size() == 2) return List.copyOf(result);
                }
            }
        } catch (RuntimeException ignored) { return List.of(); }
        return List.copyOf(result);
    }

    public List<X509CRL> resolve(X509Certificate certificate, X509Certificate issuer) {
        List<X509CRL> result = new ArrayList<>();
        for (URI uri : distributionPoints(certificate)) {
            String key = CertificateValidator.fingerprint(issuer) + "|" + uri.getPath().toLowerCase(Locale.ROOT);
            synchronized (locks[(key.hashCode() & Integer.MAX_VALUE) % locks.length]) {
                Entry entry;
                synchronized (cache) { entry = cache.get(key); }
                if (entry == null || !clock.instant().isBefore(entry.until())) {
                    entry = load(uri, certificate, issuer);
                    synchronized (cache) {
                        cache.put(key, entry);
                        while (cache.size() > MAX_ENTRIES) cache.remove(cache.keySet().iterator().next());
                    }
                }
                if (entry.crl() != null) result.add(entry.crl());
            }
        }
        return result;
    }

    private Entry load(URI uri, X509Certificate certificate, X509Certificate issuer) {
        try {
            byte[] bytes = fetcher.fetch(uri);
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("CRL size");
            X509CRL crl = (X509CRL) CertificateFactory.getInstance("X.509").generateCRL(new ByteArrayInputStream(bytes));
            if (!java.util.Arrays.equals(crl.getEncoded(), bytes) || !crl.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())) {
                throw new IllegalArgumentException("CRL issuer or encoding");
            }
            crl.verify(issuer.getPublicKey());
            Instant now = clock.instant();
            if (crl.getThisUpdate() == null || crl.getThisUpdate().toInstant().isAfter(now)
                    || crl.getNextUpdate() == null || !now.isBefore(crl.getNextUpdate().toInstant())
                    || !CertificateValidator.supportedScope(crl, certificate)) throw new IllegalArgumentException("CRL validity or scope");
            Instant refresh = now.plus(Duration.ofMinutes(5));
            return new Entry(crl, refresh.isBefore(crl.getNextUpdate().toInstant()) ? refresh : crl.getNextUpdate().toInstant());
        } catch (Exception failure) {
            // Do not log certificates, URLs, request bodies or exception text containing provider responses.
            LoggerFactory.getLogger(YessignCrlResolver.class).warn("Certificate CRL lookup unavailable: provider=yessign, reason={}",
                    failure.getClass().getSimpleName());
            return new Entry(null, clock.instant().plusSeconds(20));
        }
    }

    private static byte[] download(URI uri) throws Exception {
        if (!allowed(uri)) throw new IllegalArgumentException("CRL destination");
        for (InetAddress address : InetAddress.getAllByName(HOST)) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()) throw new IllegalArgumentException("CRL network");
        }
        Hashtable<String, String> environment = new Hashtable<>();
        environment.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        environment.put(Context.PROVIDER_URL, "ldap://" + HOST + ":389");
        environment.put(Context.REFERRAL, "ignore");
        environment.put("com.sun.jndi.ldap.connect.timeout", "3000");
        environment.put("com.sun.jndi.ldap.read.timeout", "4000");
        environment.put("java.naming.ldap.attributes.binary", "certificateRevocationList");
        InitialDirContext context = new InitialDirContext(environment);
        try {
            var attributes = context.getAttributes(uri.getPath().substring(1), new String[]{"certificateRevocationList;binary"});
            var value = attributes.get("certificateRevocationList;binary");
            if (value == null) value = attributes.get("certificateRevocationList");
            if (value == null || value.size() != 1 || !(value.get() instanceof byte[] bytes) || bytes.length > MAX_BYTES) {
                throw new IllegalArgumentException("CRL attribute");
            }
            return bytes;
        } finally { context.close(); }
    }
}
