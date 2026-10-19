package com.inviz.visioncerti.runtime;

import com.inviz.visioncerti.doctor.credential.OpenBaoProperties;
import com.inviz.visioncerti.security.StaffAuthConfig.OidcSettings;
import com.inviz.visioncerti.security.SyntheticActorsCondition;
import com.inviz.visioncerti.store.Store;
import com.inviz.visioncerti.store.StoreConfig;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.cert.CRL;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Startup gate for the {@code prod} profile. Every requirement is checked and all failures are reported together
 * (variable names only, never values); any failure aborts startup through
 * {@link com.inviz.visioncerti.security.FailClosedProfileGuard}. Runtime beans repeat their own checks (storage schema,
 * OpenBao settings), so a bypass of this class still fails closed.
 *
 * <p>Requirements: PostgreSQL with TLS verification (unless loopback) and a password file; OIDC staff authentication
 * over https; OpenBao Transit + PKI over https with a CA file and separate provisioning/signing token files; PC
 * certificate trust anchors with a fresh CRL for every anchor (no mobile adapter is implemented, so a configured
 * mobile provider is an error); no dev identity, mock certificate, synthetic actor, simulated signing or dev policy.
 */
public final class ProductionReadiness {

    private ProductionReadiness() {
    }

    public static boolean isProd(Environment environment) {
        return environment.acceptsProfiles(Profiles.of("prod", "production"));
    }

    public static List<String> problems(Environment env, Instant now) {
        List<String> problems = new ArrayList<>();
        if (env.acceptsProfiles(Profiles.of("dev"))) {
            problems.add("the dev profile cannot be combined with prod");
        }
        requireFalse(env, problems, "visioncerti.dev.synthetic-identities-enabled",
                "visioncerti.dev.synthetic-identities-enabled must be false in prod");
        requireFalse(env, problems, "visioncerti.doctor.dev-identity-enabled",
                "VISIONCERTI_DOCTOR_DEV_IDENTITY_ENABLED must be false");
        requireFalse(env, problems, "visioncerti.cert.dev-mock-enabled",
                "VISIONCERTI_CERT_DEV_MOCK_ENABLED must be false");
        String policy = upper(env.getProperty("visioncerti.doctor.activation-policy", ""));
        if (!policy.isEmpty() && !policy.equals("CERTIFICATE_REQUIRED")) {
            problems.add("VISIONCERTI_DOCTOR_ACTIVATION_POLICY must be CERTIFICATE_REQUIRED");
        }

        // Persistence.
        String mode = upper(env.getProperty("visioncerti.persistence.mode", ""));
        if (!mode.isEmpty() && !mode.equals(Store.MODE_POSTGRESQL)) {
            problems.add("VISIONCERTI_PERSISTENCE_MODE must be POSTGRESQL (or unset)");
        }
        String db = StoreConfig.problem(env.getProperty("visioncerti.db.url"),
                env.getProperty("visioncerti.db.username"), env.getProperty("visioncerti.db.password-file"), true);
        if (db != null) {
            problems.add(db);
        }

        // Staff authentication.
        if (!SyntheticActorsCondition.OIDC.equals(upper(env.getProperty(SyntheticActorsCondition.MODE_PROPERTY, "")))) {
            problems.add("VISIONCERTI_STAFF_AUTH_MODE must be OIDC");
        }
        String oidc = OidcSettings.problem(env.getProperty("visioncerti.staff-auth.oidc.issuer"),
                env.getProperty("visioncerti.staff-auth.oidc.audience"),
                env.getProperty("visioncerti.staff-auth.oidc.jwks-uri"), true);
        if (oidc != null) {
            problems.add(oidc);
        }
        if (blank(env.getProperty("visioncerti.staff-auth.oidc.client-id"))) {
            problems.add("VISIONCERTI_STAFF_OIDC_CLIENT_ID (public client id for the web app) is not set");
        }

        // Signing keys: OpenBao Transit + PKI only.
        if (!"OPENBAO_TRANSIT".equals(upper(env.getProperty("visioncerti.doctor.signing-backend", "")))) {
            problems.add("VISIONCERTI_SIGNING_BACKEND must be OPENBAO_TRANSIT");
        }
        problems.addAll(openBaoProblems(env));

        // Physician authentication material.
        problems.addAll(certificateProblems(env.getProperty("visioncerti.cert.trust-anchors-file"),
                env.getProperty("visioncerti.cert.crl-files"), now));
        if (!blank(env.getProperty("visioncerti.mobile.provider"))) {
            problems.add("VISIONCERTI_MOBILE_PROVIDER is set but no mobile adapter is implemented; leave it empty");
        }

        // Service integration secrets come from files only.
        if (!blank(env.getProperty("visioncerti.pacs.client-secret"))) {
            problems.add("VISIONCERTI_PACS_CLIENT_SECRET must not be set in prod; use VISIONCERTI_PACS_CLIENT_SECRET_FILE");
        }
        return problems;
    }

    static List<String> openBaoProblems(Environment env) {
        List<String> problems = new ArrayList<>();
        if (!blank(env.getProperty("visioncerti.openbao.provisioning-token"))
                || !blank(env.getProperty("visioncerti.openbao.signing-token"))) {
            problems.add("OpenBao tokens must come from VISIONCERTI_OPENBAO_PROVISIONING_TOKEN_FILE and "
                    + "VISIONCERTI_OPENBAO_SIGNING_TOKEN_FILE, not environment values");
        }
        String provisioning = readSecret(env.getProperty("visioncerti.openbao.provisioning-token-file"));
        String signing = readSecret(env.getProperty("visioncerti.openbao.signing-token-file"));
        if (provisioning == null || signing == null) {
            problems.add("VISIONCERTI_OPENBAO_PROVISIONING_TOKEN_FILE and VISIONCERTI_OPENBAO_SIGNING_TOKEN_FILE must "
                    + "name readable, non-empty files");
        }
        String addr = env.getProperty("visioncerti.openbao.addr", "");
        if (!addr.trim().toLowerCase(Locale.ROOT).startsWith("https://")) {
            problems.add("VISIONCERTI_OPENBAO_ADDR must use https in prod");
        }
        String caFile = env.getProperty("visioncerti.openbao.ca-cert-file", "");
        if (blank(caFile) || !Files.isReadable(Path.of(caFile.trim()))) {
            problems.add("VISIONCERTI_OPENBAO_CA_CERT_FILE must name a readable CA certificate file");
        }
        if (provisioning != null && signing != null) {
            OpenBaoProperties properties = new OpenBaoProperties(addr, env.getProperty("visioncerti.openbao.namespace"),
                    provisioning, signing, env.getProperty("visioncerti.openbao.transit-mount", "transit"),
                    env.getProperty("visioncerti.openbao.pki-mount", "pki"),
                    env.getProperty("visioncerti.openbao.pki-role", "vision-certi-doctor"),
                    env.getProperty("visioncerti.openbao.key-prefix", "vc-doctor"),
                    env.getProperty("visioncerti.openbao.cert-ttl", "8760h"), caFile,
                    env.getProperty("visioncerti.openbao.timeout", Duration.class, Duration.ofSeconds(10)));
            String problem = properties.problem();
            if (problem != null) {
                problems.add(problem);
            }
        }
        return problems;
    }

    /** Trust anchors present, and every anchor has a CRL (issued by it) whose nextUpdate is in the future. */
    public static List<String> certificateProblems(String anchorsFile, String crlFiles, Instant now) {
        List<String> problems = new ArrayList<>();
        List<X509Certificate> anchors = new ArrayList<>();
        if (!blank(anchorsFile)) {
            try {
                for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(
                        new ByteArrayInputStream(Files.readAllBytes(Path.of(anchorsFile.trim()))))) {
                    X509Certificate x = (X509Certificate) c;
                    if (x.getBasicConstraints() >= 0) {
                        anchors.add(x);
                    }
                }
            } catch (IOException | GeneralSecurityException e) {
                anchors.clear();
            }
        }
        if (anchors.isEmpty()) {
            problems.add("VISIONCERTI_CERT_TRUST_ANCHORS_FILE must contain at least one CA certificate");
            return problems;
        }
        List<X509CRL> crls = new ArrayList<>();
        if (!blank(crlFiles)) {
            for (String file : Arrays.stream(crlFiles.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()) {
                try {
                    for (CRL crl : CertificateFactory.getInstance("X.509").generateCRLs(
                            new ByteArrayInputStream(Files.readAllBytes(Path.of(file))))) {
                        crls.add((X509CRL) crl);
                    }
                } catch (IOException | GeneralSecurityException e) {
                    problems.add("a file in VISIONCERTI_CERT_CRL_FILES is missing or not a CRL");
                }
            }
        }
        for (X509Certificate anchor : anchors) {
            boolean fresh = crls.stream().anyMatch(crl -> crl.getIssuerX500Principal().equals(
                    anchor.getSubjectX500Principal()) && verifies(crl, anchor) && crl.getNextUpdate() != null
                    && crl.getNextUpdate().toInstant().isAfter(now));
            if (!fresh) {
                problems.add("no fresh, authentic CRL (nextUpdate in the future) for a configured trust anchor");
                break;
            }
        }
        return problems;
    }

    /** FRESH when every CRL's nextUpdate is in the future, STALE when one is not, NONE without CRLs. */
    public static String crlState(List<X509CRL> crls, Instant now) {
        if (crls.isEmpty()) {
            return "NONE";
        }
        return crls.stream().allMatch(c -> c.getNextUpdate() != null && c.getNextUpdate().toInstant().isAfter(now))
                ? "FRESH" : "STALE";
    }

    private static boolean verifies(X509CRL crl, X509Certificate issuer) {
        try {
            crl.verify(issuer.getPublicKey());
            return true;
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private static String readSecret(String file) {
        if (blank(file)) {
            return null;
        }
        try {
            String value = Files.readString(Path.of(file.trim()), StandardCharsets.UTF_8).strip();
            return value.isEmpty() ? null : value;
        } catch (IOException e) {
            return null;
        }
    }

    private static void requireFalse(Environment env, List<String> problems, String property, String message) {
        if (Boolean.TRUE.equals(env.getProperty(property, Boolean.class, false))) {
            problems.add(message);
        }
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
