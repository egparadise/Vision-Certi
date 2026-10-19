package com.inviz.visioncerti.admin;

import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.security.ActorRole;
import com.inviz.visioncerti.security.RequestActor;
import com.inviz.visioncerti.store.Store;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of the deployment configuration for administrators. These values are changed only through the
 * deployment environment (and a restart) because they are security boundaries, endpoints or secrets. Secret values
 * and file paths are never returned: only whether they are configured.
 */
@RestController
public class SystemConfigurationController {

    /** {@code value} is null for secrets and file paths; {@code configured} says whether a value is present. */
    public record ConfigItem(String group, String key, String envVar, String label, String value, boolean configured,
            boolean secret) {
    }

    public record SystemConfigurationResponse(List<String> activeProfiles, String persistenceMode,
            List<String> settingsEditorTenants, boolean settingsEditable, List<ConfigItem> items,
            Instant generatedAt) {
    }

    private enum Kind { VALUE, SECRET }

    private record Entry(String group, String property, String envVar, String label, Kind kind, String fallback) {
    }

    private static final List<Entry> ENTRIES = List.of(
            new Entry("PERSISTENCE", "visioncerti.persistence.mode", "VISIONCERTI_PERSISTENCE_MODE", "영속성 모드", Kind.VALUE, ""),
            new Entry("PERSISTENCE", "visioncerti.db.url", "VISIONCERTI_DB_URL", "데이터베이스 URL", Kind.SECRET, ""),
            new Entry("PERSISTENCE", "visioncerti.db.username", "VISIONCERTI_DB_USERNAME", "데이터베이스 로그인", Kind.SECRET, ""),
            new Entry("PERSISTENCE", "visioncerti.db.password-file", "VISIONCERTI_DB_PASSWORD_FILE", "데이터베이스 비밀번호 파일", Kind.SECRET, ""),
            new Entry("PERSISTENCE", "visioncerti.db.pool-size", "VISIONCERTI_DB_POOL_SIZE", "연결 풀 크기", Kind.VALUE, "10"),
            new Entry("PERSISTENCE", "visioncerti.db.lock-timeout", "VISIONCERTI_DB_LOCK_TIMEOUT", "잠금 대기 시간", Kind.VALUE, "PT5S"),
            new Entry("STAFF_AUTH", "visioncerti.staff-auth.mode", "VISIONCERTI_STAFF_AUTH_MODE", "관리자 인증 방식", Kind.VALUE, "DEV_HEADERS"),
            new Entry("STAFF_AUTH", "visioncerti.staff-auth.oidc.issuer", "VISIONCERTI_STAFF_OIDC_ISSUER", "OIDC 발급자", Kind.VALUE, ""),
            new Entry("STAFF_AUTH", "visioncerti.staff-auth.oidc.audience", "VISIONCERTI_STAFF_OIDC_AUDIENCE", "OIDC 대상(audience)", Kind.VALUE, ""),
            new Entry("STAFF_AUTH", "visioncerti.staff-auth.oidc.client-id", "VISIONCERTI_STAFF_OIDC_CLIENT_ID", "OIDC 클라이언트 ID", Kind.VALUE, ""),
            new Entry("STAFF_AUTH", "visioncerti.staff-auth.oidc.jwks-uri", "VISIONCERTI_STAFF_OIDC_JWKS_URI", "OIDC JWKS URI", Kind.VALUE, ""),
            new Entry("STAFF_AUTH", "visioncerti.staff-auth.bootstrap-file", "VISIONCERTI_STAFF_BOOTSTRAP_FILE", "관리자 초기 등록 파일", Kind.SECRET, ""),
            new Entry("STAFF_AUTH", "visioncerti.admin.settings-tenant-ids", "VISIONCERTI_ADMIN_SETTINGS_TENANT_IDS", "운영 정책 변경 가능 기관", Kind.VALUE, ""),
            new Entry("DOCTORS", "visioncerti.doctor.activation-policy", "VISIONCERTI_DOCTOR_ACTIVATION_POLICY", "의사 활성화 정책", Kind.VALUE, "CERTIFICATE_REQUIRED"),
            new Entry("DOCTORS", "visioncerti.doctor.admin-invitations-enabled", "VISIONCERTI_ADMIN_INVITATIONS_ENABLED", "관리자 사전 등록·초대", Kind.VALUE, "false"),
            new Entry("DOCTORS", "visioncerti.physician-account.tenant-id", "VISIONCERTI_PHYSICIAN_ACCOUNT_TENANT_ID", "의사 직접 가입 기관", Kind.VALUE, ""),
            new Entry("DOCTORS", "visioncerti.doctor.google.client-id", "VISIONCERTI_GOOGLE_CLIENT_ID", "Google 클라이언트 ID", Kind.VALUE, ""),
            new Entry("CERTIFICATES", "visioncerti.cert.service-id", "VISIONCERTI_CERT_SERVICE_ID", "인증서 서비스 ID", Kind.VALUE, "vision-certi-dev"),
            new Entry("CERTIFICATES", "visioncerti.cert.challenge-ttl", "VISIONCERTI_CERT_CHALLENGE_TTL", "인증서 확인 요청 유효 시간", Kind.VALUE, "PT5M"),
            new Entry("CERTIFICATES", "visioncerti.cert.trust-anchors-file", "VISIONCERTI_CERT_TRUST_ANCHORS_FILE", "신뢰 루트(CA) 파일", Kind.SECRET, ""),
            new Entry("CERTIFICATES", "visioncerti.cert.crl-files", "VISIONCERTI_CERT_CRL_FILES", "인증서 폐지 목록(CRL) 파일", Kind.SECRET, ""),
            new Entry("CERTIFICATES", "visioncerti.mobile.provider", "VISIONCERTI_MOBILE_PROVIDER", "모바일 인증서 제공자", Kind.VALUE, ""),
            new Entry("SIGNING", "visioncerti.doctor.signing-backend", "VISIONCERTI_SIGNING_BACKEND", "서명 백엔드", Kind.VALUE, "NOT_CONFIGURED"),
            new Entry("SIGNING", "visioncerti.openbao.addr", "VISIONCERTI_OPENBAO_ADDR", "OpenBao 주소", Kind.VALUE, ""),
            new Entry("SIGNING", "visioncerti.openbao.namespace", "VISIONCERTI_OPENBAO_NAMESPACE", "OpenBao 네임스페이스", Kind.VALUE, ""),
            new Entry("SIGNING", "visioncerti.openbao.transit-mount", "VISIONCERTI_OPENBAO_TRANSIT_MOUNT", "Transit 마운트", Kind.VALUE, "transit"),
            new Entry("SIGNING", "visioncerti.openbao.pki-mount", "VISIONCERTI_OPENBAO_PKI_MOUNT", "PKI 마운트", Kind.VALUE, "pki"),
            new Entry("SIGNING", "visioncerti.openbao.pki-role", "VISIONCERTI_OPENBAO_PKI_ROLE", "PKI 역할", Kind.VALUE, "vision-certi-doctor"),
            new Entry("SIGNING", "visioncerti.openbao.key-prefix", "VISIONCERTI_OPENBAO_KEY_PREFIX", "서명 키 이름 접두사", Kind.VALUE, "vc-doctor"),
            new Entry("SIGNING", "visioncerti.openbao.cert-ttl", "VISIONCERTI_OPENBAO_CERT_TTL", "서명 인증서 유효 기간", Kind.VALUE, "8760h"),
            new Entry("SIGNING", "visioncerti.openbao.timeout", "VISIONCERTI_OPENBAO_TIMEOUT", "OpenBao 요청 제한 시간", Kind.VALUE, "PT10S"),
            new Entry("SIGNING", "visioncerti.openbao.provisioning-token-file", "VISIONCERTI_OPENBAO_PROVISIONING_TOKEN_FILE", "OpenBao 발급 토큰 파일", Kind.SECRET, ""),
            new Entry("SIGNING", "visioncerti.openbao.signing-token-file", "VISIONCERTI_OPENBAO_SIGNING_TOKEN_FILE", "OpenBao 서명 토큰 파일", Kind.SECRET, ""),
            new Entry("SIGNING", "visioncerti.openbao.ca-cert-file", "VISIONCERTI_OPENBAO_CA_CERT_FILE", "OpenBao CA 인증서 파일", Kind.SECRET, ""),
            new Entry("PACS", "visioncerti.pacs.client-id", "VISIONCERTI_PACS_CLIENT_ID", "PACS 연동 클라이언트 ID", Kind.VALUE, ""),
            new Entry("PACS", "visioncerti.pacs.audience", "VISIONCERTI_PACS_AUDIENCE", "PACS 연동 대상(audience)", Kind.VALUE, "vision-certi:pacs-integration:v1"),
            new Entry("PACS", "visioncerti.pacs.tenant-ids", "VISIONCERTI_PACS_TENANT_IDS", "PACS 연동 허용 기관", Kind.VALUE, ""),
            new Entry("PACS", "visioncerti.pacs.client-secret-file", "VISIONCERTI_PACS_CLIENT_SECRET_FILE", "PACS 연동 비밀키 파일", Kind.SECRET, ""));

    private final Environment environment;
    private final Store store;
    private final SystemSettings settings;

    public SystemConfigurationController(Environment environment, Store store, SystemSettings settings) {
        this.environment = environment;
        this.store = store;
        this.settings = settings;
    }

    @GetMapping("/v1/admin/system/configuration")
    public ResponseEntity<SystemConfigurationResponse> configuration(RequestActor actor) {
        if (actor.role() != ActorRole.ADMIN) {
            throw ApiException.forbidden("ADMIN_ROLE_REQUIRED", "Administrator role required");
        }
        List<ConfigItem> items = new ArrayList<>();
        for (Entry e : ENTRIES) {
            String raw = environment.getProperty(e.property(), e.fallback());
            boolean configured = StringUtils.hasText(raw);
            boolean secret = e.kind() == Kind.SECRET;
            items.add(new ConfigItem(e.group(), e.property(), e.envVar(), e.label(),
                    secret || !configured ? null : raw.strip(), configured, secret));
        }
        // OpenBao tokens may also be given inline (deprecated); report only presence.
        boolean inlineTokens = StringUtils.hasText(environment.getProperty("visioncerti.openbao.provisioning-token"))
                || StringUtils.hasText(environment.getProperty("visioncerti.openbao.signing-token"));
        items.add(new ConfigItem("SIGNING", "visioncerti.openbao.*-token", "VISIONCERTI_OPENBAO_*_TOKEN",
                "OpenBao 토큰 직접 입력(비권장)", null, inlineTokens, true));
        SystemConfigurationResponse body = new SystemConfigurationResponse(
                Arrays.asList(environment.getActiveProfiles()), store.mode(), List.copyOf(settings.editorTenants()),
                settings.editableBy(actor), items, Instant.now());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
