package com.inviz.visioncerti.runtime;

import com.inviz.visioncerti.certificate.CertificateEnrollmentService;
import com.inviz.visioncerti.certificate.CertificateValidator;
import com.inviz.visioncerti.certificate.MobileIdentityProvider;
import com.inviz.visioncerti.doctor.DoctorService;
import com.inviz.visioncerti.doctor.credential.DoctorCredentialProvider;
import com.inviz.visioncerti.doctor.identity.GoogleIdTokenVerifier;
import com.inviz.visioncerti.enrollment.SmsSender;
import com.inviz.visioncerti.integration.IntegrationClients;
import com.inviz.visioncerti.security.SyntheticActorsCondition;
import com.inviz.visioncerti.store.PostgresStore;
import com.inviz.visioncerti.store.Store;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, secret-free description of what this running instance actually is: profile, persistence, staff and
 * physician authentication, signing backend and the limits of what its signatures mean. {@code clinicalUseAllowed}
 * is always false: the service makes no claim of legal approval. {@code warnings} are computed from the live
 * configuration and must be shown as given; they are never suppressed for presentation.
 */
@RestController
public class RuntimeCapabilitiesController {

    public record Persistence(String mode, Integer schemaVersion, String state) {
    }

    public record StaffAuth(String mode, String issuer, String clientId, String audience, List<String> scopes,
            String acceptedTokens) {
    }

    public record PcCertificate(boolean configured, int trustAnchors, int crls, String crlState) {
    }

    public record Mobile(boolean configured, String provider) {
    }

    public record DoctorAuth(PcCertificate pcCertificate, Mobile mobile, boolean googleConfigured,
            boolean devMockCertificate, boolean devIdentity, String activationPolicy) {
    }

    public record Signing(String backend, boolean available, String signatureMode, String pki, String timestamp,
            String verificationLabel) {
    }

    public record Feature(boolean available, String reason) {
    }

    public record Capabilities(String profile, List<String> activeProfiles, Persistence persistence,
            StaffAuth staffAuth, DoctorAuth doctorAuth, Signing signing, Feature personalSignup,
            Feature pacsIntegration, boolean clinicalUseAllowed, String clinicalUseReason, List<String> warnings,
            Instant generatedAt) {
    }

    private final Environment environment;
    private final Store store;
    private final CertificateValidator validator;
    private final MobileIdentityProvider mobile;
    private final GoogleIdTokenVerifier google;
    private final DoctorCredentialProvider credentials;
    private final SmsSender sms;
    private final IntegrationClients integrationClients;
    private final CertificateEnrollmentService certificates;
    private final DoctorService doctors;
    private final Clock clock;
    private final String staffMode;
    private final String staffIssuer;
    private final String staffClientId;
    private final String staffAudience;

    public RuntimeCapabilitiesController(Environment environment, Store store, CertificateValidator validator,
            MobileIdentityProvider mobile, GoogleIdTokenVerifier google, DoctorCredentialProvider credentials,
            SmsSender sms, IntegrationClients integrationClients, CertificateEnrollmentService certificates,
            DoctorService doctors, Clock clock,
            @Value("${visioncerti.staff-auth.mode:DEV_HEADERS}") String staffMode,
            @Value("${visioncerti.staff-auth.oidc.issuer:}") String staffIssuer,
            @Value("${visioncerti.staff-auth.oidc.client-id:}") String staffClientId,
            @Value("${visioncerti.staff-auth.oidc.audience:}") String staffAudience) {
        this.environment = environment;
        this.store = store;
        this.validator = validator;
        this.mobile = mobile;
        this.google = google;
        this.credentials = credentials;
        this.sms = sms;
        this.integrationClients = integrationClients;
        this.certificates = certificates;
        this.doctors = doctors;
        this.clock = clock;
        this.staffMode = staffMode.trim().toUpperCase(Locale.ROOT);
        this.staffIssuer = blankToNull(staffIssuer);
        this.staffClientId = blankToNull(staffClientId);
        this.staffAudience = blankToNull(staffAudience);
    }

    @GetMapping("/v1/runtime/capabilities")
    public ResponseEntity<Capabilities> capabilities() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(compute());
    }

    Capabilities compute() {
        Instant now = clock.instant();
        boolean prod = ProductionReadiness.isProd(environment);
        List<String> warnings = new ArrayList<>();

        boolean postgres = Store.MODE_POSTGRESQL.equals(store.mode());
        Persistence persistence = new Persistence(store.mode(), postgres ? PostgresStore.SCHEMA_VERSION : null,
                "READY");
        if (!postgres) {
            warnings.add("IN_MEMORY_PERSISTENCE_NOT_DURABLE");
        }

        boolean oidc = SyntheticActorsCondition.OIDC.equals(staffMode);
        StaffAuth staffAuth = oidc
                ? new StaffAuth("OIDC", staffIssuer, staffClientId, staffAudience, List.of("openid", "profile"),
                        "JWT (RS256|ES256|PS256) with iss, aud, exp, iat; Authorization: Bearer")
                : new StaffAuth(SyntheticActorsCondition.DEV_HEADERS, null, null, null, List.of(),
                        "X-Tenant-Id / X-Actor-Id synthetic dev headers (not authentication)");
        if (!oidc) {
            warnings.add("DEV_HEADER_IDENTITIES_NOT_AUTHENTICATION");
        }

        int anchors = validator.trust().trustAnchors().size();
        var crls = validator.trust().crls();
        String crlState = ProductionReadiness.crlState(crls, now);
        PcCertificate pc = new PcCertificate(anchors > 0, anchors, crls.size(), crlState);
        if (anchors == 0) {
            warnings.add("PC_CERTIFICATE_TRUST_NOT_CONFIGURED");
        } else if (!"FRESH".equals(crlState)) {
            warnings.add("CRL_" + crlState + "_CERTIFICATE_CHECKS_FAIL_CLOSED");
        }
        Mobile mobileState = new Mobile(mobile.configured(), mobile.configured() ? mobile.name() : null);
        if (!mobile.configured()) {
            warnings.add("MOBILE_PROVIDER_NOT_CONFIGURED");
        }
        DoctorAuth doctorAuth = new DoctorAuth(pc, mobileState, google.configured(), certificates.devMockEnabled(),
                doctors.devIdentityEnabled(), doctors.activationPolicy());
        if (certificates.devMockEnabled() || doctors.devIdentityEnabled()) {
            warnings.add("DEV_MOCK_IDENTITY_ROUTES_ENABLED");
        }

        String backend = credentials.backend();
        String pki = switch (backend) {
            case DoctorCredentialProvider.OPENBAO_TRANSIT -> "PRIVATE_OPENBAO_PKI";
            case DoctorCredentialProvider.DEV_SIMULATED -> "SIMULATED_NOT_X509";
            default -> null;
        };
        Signing signing = new Signing(backend, credentials.available(), credentials.signatureMode(), pki, "NONE",
                "PRIVATE_PKI_NOT_TIMESTAMPED");
        if (!credentials.available()) {
            warnings.add("SIGNING_BACKEND_NOT_AVAILABLE");
        }
        if (DoctorCredentialProvider.DEV_SIMULATED.equals(backend)) {
            warnings.add("SIMULATED_SIGNING_KEYS");
        }
        warnings.add("PRIVATE_PKI_NOT_ACCREDITED");
        warnings.add("NO_TRUSTED_TIMESTAMP");
        warnings.add("RAW_ECDSA_NOT_CADES_PADES");

        Feature signup = sms.available() ? new Feature(true, sms.mode()) : new Feature(false, "SMS_NOT_CONFIGURED");
        if (sms.available() && sms.revealsCodeToClient()) {
            warnings.add("MOCK_SMS_CODES_RETURNED_TO_CLIENT");
        }
        Feature pacs = integrationClients.configured() ? new Feature(true, null)
                : new Feature(false, "INTEGRATION_NOT_CONFIGURED");

        return new Capabilities(prod ? "prod" : String.join(",", environment.getActiveProfiles()),
                Arrays.asList(environment.getActiveProfiles()), persistence, staffAuth, doctorAuth, signing, signup,
                pacs, false, "LEGAL_APPROVAL_NOT_ASSERTED", List.copyOf(warnings), now);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
