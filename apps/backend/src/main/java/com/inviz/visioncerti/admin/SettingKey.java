package com.inviz.visioncerti.admin;

import java.util.Arrays;
import java.util.Optional;

/**
 * Operational policy values an administrator may change at runtime. Each has a hard range; the deployment
 * configuration (application.yml / environment) supplies the default. Security switches, endpoints, secrets and
 * file paths are deliberately not here: they stay deployment-only (see {@link SystemConfigurationController}).
 */
public enum SettingKey {

    DOCUMENT_MAX_BYTES("documents.max-bytes", Group.DOCUMENTS, Unit.BYTES, 1_048_576L, 20_971_520L,
            "판독문 최대 파일 크기", "판독문 등록·개정 시 허용하는 PDF 최대 크기입니다. 업로드 요청 크기 상한(20MB)을 넘을 수 없습니다."),
    SIGN_REQUEST_TTL("sign-requests.ttl", Group.SIGNING, Unit.SECONDS, 60L, 3_600L,
            "서명 요청 유효 시간", "의사 승인 대기 상태의 서명 요청이 만료되기까지의 시간입니다. 새로 만드는 요청부터 적용됩니다."),
    PACS_HANDOFF_TTL("pacs.handoff-ttl", Group.PACS, Unit.SECONDS, 15L, 300L,
            "PACS 핸드오프 코드 유효 시간", "인증서 로그인 후 PACS로 넘기는 1회용 코드의 유효 시간입니다."),
    PACS_MAX_CERTIFICATE_LOGIN_AGE("pacs.max-certificate-login-age", Group.PACS, Unit.SECONDS, 60L, 3_600L,
            "PACS 연동 인증서 로그인 최대 경과 시간", "이 시간보다 오래된 인증서 로그인으로는 PACS 핸드오프를 발급하지 않습니다."),
    DOCTOR_INVITATION_TTL("doctor.invitation-ttl", Group.DOCTORS, Unit.SECONDS, 3_600L, 604_800L,
            "의사 초대 코드 유효 시간", "의사 사전 등록·재발급 시 발급하는 초대 코드의 유효 시간입니다."),
    DOCTOR_SESSION_TTL("doctor.session-ttl", Group.DOCTORS, Unit.SECONDS, 300L, 28_800L,
            "의사 인증서 로그인 세션 시간", "인증서 로그인으로 발급되는 의사 세션의 유효 시간입니다. 새로 발급되는 세션부터 적용됩니다."),
    DOCTOR_MAX_IDENTITY_FAILURES("doctor.max-identity-failures-per-invitation", Group.DOCTORS, Unit.COUNT, 1L, 20L,
            "초대 코드당 본인 확인 실패 허용 횟수", "이 횟수를 넘으면 해당 초대 코드로는 더 이상 본인 확인을 시도할 수 없습니다."),
    DOCTOR_MAX_CHALLENGES_PER_INVITATION("doctor.max-challenges-per-invitation", Group.DOCTORS, Unit.COUNT, 1L, 50L,
            "초대 코드당 인증 요청 허용 횟수", "초대 코드 하나로 만들 수 있는 인증서 확인 요청 수입니다."),
    DOCTOR_SOURCE_FAILURE_HOURLY_LIMIT("doctor.source-failure-hourly-limit", Group.RATE_LIMITS, Unit.COUNT, 1L, 1_000L,
            "접속지별 시간당 인증 실패 한도", "같은 접속지에서 1시간 동안 허용하는 초대·인증 실패 횟수입니다."),
    DOCTOR_SOURCE_CHALLENGE_HOURLY_LIMIT("doctor.source-challenge-hourly-limit", Group.RATE_LIMITS, Unit.COUNT, 1L,
            1_000L, "접속지별 시간당 인증 요청 한도", "같은 접속지에서 1시간 동안 만들 수 있는 인증 요청 수입니다."),
    ENROLLMENT_PHONE_HOURLY_LIMIT("enrollment.phone-hourly-limit", Group.RATE_LIMITS, Unit.COUNT, 1L, 50L,
            "전화번호별 시간당 인증 문자 한도", "같은 전화번호로 1시간 동안 보낼 수 있는 인증 문자 수입니다."),
    ENROLLMENT_SOURCE_HOURLY_LIMIT("enrollment.source-hourly-limit", Group.RATE_LIMITS, Unit.COUNT, 1L, 1_000L,
            "접속지별 시간당 인증 문자 한도", "같은 접속지에서 1시간 동안 보낼 수 있는 인증 문자 수입니다.");

    public enum Group { DOCUMENTS, SIGNING, PACS, DOCTORS, RATE_LIMITS }

    public enum Unit { BYTES, SECONDS, COUNT }

    private final String id;
    private final Group group;
    private final Unit unit;
    private final long min;
    private final long max;
    private final String label;
    private final String description;

    SettingKey(String id, Group group, Unit unit, long min, long max, String label, String description) {
        this.id = id;
        this.group = group;
        this.unit = unit;
        this.min = min;
        this.max = max;
        this.label = label;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public Group group() {
        return group;
    }

    public Unit unit() {
        return unit;
    }

    public long min() {
        return min;
    }

    public long max() {
        return max;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public long clamp(long value) {
        return Math.max(min, Math.min(max, value));
    }

    public static Optional<SettingKey> byId(String id) {
        return Arrays.stream(values()).filter(k -> k.id.equals(id)).findFirst();
    }
}
