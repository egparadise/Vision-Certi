import type { SettingGroup, SettingUnit } from '../../types/admin';

export const SETTING_GROUP_LABELS: Record<SettingGroup, string> = {
  DOCUMENTS: '판독문',
  SIGNING: '전자서명',
  PACS: 'PACS 연동',
  DOCTORS: '의사 계정·인증',
  RATE_LIMITS: '접속·발송 제한',
};

export const CONFIG_GROUP_LABELS: Record<string, string> = {
  PERSISTENCE: '데이터 저장소',
  STAFF_AUTH: '관리자 인증',
  DOCTORS: '의사 가입·활성화',
  CERTIFICATES: '인증서(CA·CRL)',
  SIGNING: '서명 키(OpenBao)',
  PACS: 'PACS 연동',
};

export const UNIT_LABELS: Record<SettingUnit, string> = {
  BYTES: '바이트',
  SECONDS: '초',
  COUNT: '회',
};

export function formatSeconds(total: number): string {
  if (!Number.isFinite(total)) return '-';
  const days = Math.floor(total / 86_400);
  const hours = Math.floor((total % 86_400) / 3_600);
  const minutes = Math.floor((total % 3_600) / 60);
  const seconds = total % 60;
  const parts: string[] = [];
  if (days) parts.push(`${days}일`);
  if (hours) parts.push(`${hours}시간`);
  if (minutes) parts.push(`${minutes}분`);
  if (seconds || parts.length === 0) parts.push(`${seconds}초`);
  return parts.join(' ');
}

export function formatBytes(bytes: number): string {
  if (bytes >= 1_048_576) return `${(bytes / 1_048_576).toFixed(bytes % 1_048_576 === 0 ? 0 : 1)} MB`;
  if (bytes >= 1_024) return `${(bytes / 1_024).toFixed(0)} KB`;
  return `${bytes} B`;
}

export function formatSettingValue(unit: SettingUnit, value: number): string {
  switch (unit) {
    case 'BYTES':
      return formatBytes(value);
    case 'SECONDS':
      return formatSeconds(value);
    default:
      return `${value.toLocaleString('ko-KR')}회`;
  }
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('ko-KR');
}

const WARNING_LABELS: Record<string, string> = {
  IN_MEMORY_PERSISTENCE_NOT_DURABLE: '메모리 저장소 사용 중: 서버를 다시 시작하면 데이터가 사라집니다.',
  DEV_HEADER_IDENTITIES_NOT_AUTHENTICATION: '개발용 헤더 신원 사용 중: 실제 인증이 아닙니다.',
  PC_CERTIFICATE_TRUST_NOT_CONFIGURED: 'PC 인증서 신뢰 루트(CA)가 설정되지 않았습니다.',
  MOBILE_PROVIDER_NOT_CONFIGURED: '모바일 인증서 제공자가 설정되지 않았습니다.',
  SIGNING_BACKEND_NOT_AVAILABLE: '서명 백엔드를 사용할 수 없습니다.',
  PRIVATE_PKI_NOT_ACCREDITED: '사설 PKI 서명입니다(공인 인정 인증서 아님).',
};

export function warningLabel(code: string): string {
  if (WARNING_LABELS[code]) return WARNING_LABELS[code];
  if (code.startsWith('CRL_')) return `인증서 폐지 목록(CRL) 상태 확인 필요: ${code}`;
  return code;
}
