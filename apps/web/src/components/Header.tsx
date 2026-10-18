import React from 'react';
import { ShieldAlert, Building2, UserCircle, Database, Server, LogIn, UserPlus, KeyRound, UserCheck, Users, LogOut } from 'lucide-react';
import { useApp } from '../context/AppContext';
import { DevActorId, DevTenantId } from '../types/api';
import { isDemoEnabled } from '../utils/env';
import { discoverOidc } from '../services/oidcDiscovery';
import { InfoPopover } from './InfoPopover';

function generateRandomString(length: number): string {
  const array = new Uint8Array(length);
  crypto.getRandomValues(array);
  return Array.from(array, dec => ('0' + dec.toString(16)).slice(-2)).join('');
}

async function generateCodeChallenge(verifier: string): Promise<string> {
  const encoder = new TextEncoder();
  const data = encoder.encode(verifier);
  const digest = await crypto.subtle.digest('SHA-256', data);
  const bytes = new Uint8Array(digest);
  let binary = '';
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary)
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
}

export const Header: React.FC = () => {
  const {
    tenantId,
    setTenantId,
    actorId,
    setActorId,
    role,
    isMockMode,
    setIsMockMode,
    runtimeCapabilities,
    capabilitiesLoading,
    capabilitiesError,
    setStaffToken,
    staffProfile,
    setStaffProfile,
    client,
  } = useApp();

  const [showStaffModal, setShowStaffModal] = React.useState(false);
  const [inputJwt, setInputJwt] = React.useState('');
  const [showDevTokenInput, setShowDevTokenInput] = React.useState(false);
  const [staffLoginError, setStaffLoginError] = React.useState<string | null>(null);
  const [staffLoginLoading, setStaffLoginLoading] = React.useState(false);

  const startOidcPkce = async () => {
    setStaffLoginError(null);
    setStaffLoginLoading(true);

    const staffAuth = runtimeCapabilities?.staffAuth;
    if (!staffAuth || staffAuth.mode !== 'OIDC' || !staffAuth.issuer || !staffAuth.clientId) {
      setStaffLoginError('운영 OIDC IdP 설정이 불완전합니다 (OIDC IdP unconfigured).');
      setStaffLoginLoading(false);
      return;
    }

    try {
      const metadata = await discoverOidc(staffAuth.issuer);
      const verifier = generateRandomString(32);
      const challenge = await generateCodeChallenge(verifier);
      const state = generateRandomString(16);
      const nonce = generateRandomString(16);
      const redirectUri = `${window.location.origin}${window.location.pathname}`;

      const scopes = (staffAuth.scopes && staffAuth.scopes.length > 0)
        ? staffAuth.scopes.join(' ')
        : 'openid profile email';

      const authUrl = metadata.authorizationEndpoint + '?' + new URLSearchParams({
        client_id: staffAuth.clientId,
        redirect_uri: redirectUri,
        response_type: 'code',
        scope: scopes,
        code_challenge: challenge,
        code_challenge_method: 'S256',
        state,
        nonce,
      }).toString();

      const popup = window.open(authUrl, 'StaffOidcAuthPopup', 'width=600,height=750');
      if (!popup) {
        setStaffLoginError('로그인 팝업이 차단되었습니다. 브라우저 팝업을 허용해주세요.');
        setStaffLoginLoading(false);
        return;
      }

      const onMessage = async (e: MessageEvent) => {
        let expectedIssuerOrigin = '';
        try {
          expectedIssuerOrigin = new URL(staffAuth.issuer!).origin.toLowerCase();
        } catch(err) {}
        const isAllowedOrigin = (
          e.origin.toLowerCase() === expectedIssuerOrigin ||
          e.origin.toLowerCase() === window.location.origin.toLowerCase()
        );
        if (!isAllowedOrigin) return;
        if (e.source !== popup) return;

        if (e.data?.type === 'OIDC_CODE_CALLBACK') {
          window.removeEventListener('message', onMessage);
          clearInterval(pollTimer);
          try { if (!popup.closed) popup.close(); } catch(err) {}

          if (e.data.state !== state) {
            setStaffLoginError('보안 검증 실패: state 불일치');
            setStaffLoginLoading(false);
            return;
          }

          const code = e.data.code;
          try {
            const tokenRes = await fetch(metadata.tokenEndpoint, {
              method: 'POST',
              credentials: 'omit',
              redirect: 'error',
              headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
              body: new URLSearchParams({
                grant_type: 'authorization_code',
                client_id: staffAuth.clientId!,
                code,
                redirect_uri: redirectUri,
                code_verifier: verifier,
              }),
            });
            if (!tokenRes.ok) {
              throw new Error(`OIDC 토큰 교환 실패: HTTP ${tokenRes.status}`);
            }
            const tokenData = await tokenRes.json();
            const token = tokenData.access_token || tokenData.id_token;
            if (!token) {
              throw new Error('OIDC 응답에 유효한 토큰이 없습니다.');
            }

            const profile = await client.getStaffMe(token);
            setStaffToken(token);
            setStaffProfile(profile);
            setShowStaffModal(false);
          } catch(err: any) {
            setStaffLoginError(err?.message || 'OIDC 토큰 검증에 실패했습니다.');
          } finally {
            setStaffLoginLoading(false);
          }
        }
      };

      window.addEventListener('message', onMessage);

      const pollTimer = setInterval(() => {
        if (popup.closed) {
          clearInterval(pollTimer);
          window.removeEventListener('message', onMessage);
          setStaffLoginLoading(false);
        }
      }, 1000);
    } catch (err: any) {
      setStaffLoginError(err?.message || 'PKCE 요청 생성 실패');
      setStaffLoginLoading(false);
    }
  };

  const handleStaffLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!inputJwt.trim()) return;
    setStaffLoginLoading(true);
    setStaffLoginError(null);
    try {
      const profile = await client.getStaffMe(inputJwt.trim());
      setStaffToken(inputJwt.trim());
      setStaffProfile(profile);
      setShowStaffModal(false);
      setInputJwt('');
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Staff 토큰 인증에 실패했습니다.';
      setStaffLoginError(msg);
    } finally {
      setStaffLoginLoading(false);
    }
  };

  const handleStaffLogout = () => {
    setStaffToken(null);
    setStaffProfile(null);
  };

  // Derive Banner details accurately from backend capabilities
  let bannerText = 'DEV MOCK NOT FOR CLINICAL USE (개발 모의 환경 — 의료용/임상용 사용 불가)';
  let bannerBadge = isMockMode ? 'STANDALONE MOCK MODE' : 'DEV PROFILE LIVE API';

  if (!isMockMode) {
    if (capabilitiesLoading) {
      bannerText = '런타임 기능 확인 중 (Querying Runtime Capabilities...)';
      bannerBadge = 'CONNECTING';
    } else if (capabilitiesError || !runtimeCapabilities) {
      bannerText = 'RUNTIME UNAVAILABLE: 백엔드 런타임 구성 확인 불가';
      bannerBadge = 'UNCONFIGURED / OFFLINE';
    } else if (runtimeCapabilities.clinicalUseAllowed) {
      bannerText = 'PRODUCTION CLINICAL USE (의료영상 판독 전자서명 임상 운영)';
      bannerBadge = 'PRODUCTION OPERATIONAL';
    } else if (runtimeCapabilities.profile === 'prod' || runtimeCapabilities.signing?.backend === 'OPENBAO_TRANSIT') {
      const signer = runtimeCapabilities.signing?.backend || 'UNKNOWN';
      const reason = runtimeCapabilities.clinicalUseReason || 'LEGAL_APPROVAL_NOT_ASSERTED';
      bannerText = `운영 API NON-CLINICAL VALIDATION: ${signer} | 임상 전 검증 모드 (${reason})`;
      bannerBadge = `${runtimeCapabilities.profile.toUpperCase()} | ${runtimeCapabilities.persistence.mode}`;
    } else {
      bannerText = 'DEV MOCK NOT FOR CLINICAL USE (개발 모의 환경 — 의료용/임상용 사용 불가)';
      bannerBadge = 'DEV PROFILE LIVE API';
    }
  }

  return (
    <header>
      {/* 1. Mandatory Top Banner for Medical Safety */}
      <div className="dev-mock-banner" role="banner">
        <ShieldAlert size={18} />
        <span data-testid="runtime-banner-text">
          {bannerText}
        </span>
        <span className="dev-mock-badge" data-testid="runtime-banner-badge">
          {bannerBadge}
        </span>
      </div>

      {/* Warnings Banner if present in capabilities */}
      {!isMockMode && runtimeCapabilities?.warnings && runtimeCapabilities.warnings.length > 0 && (
        <div
          data-testid="runtime-warnings-banner"
          style={{
            backgroundColor: '#fffbeb',
            color: '#92400e',
            borderBottom: '1px solid #fde68a',
            padding: '4px 16px',
            fontSize: '0.75rem',
            display: 'flex',
            alignItems: 'center',
            gap: '8px',
          }}
        >
          <strong>⚠️ 런타임 보안 경고:</strong>
          <span>{runtimeCapabilities.warnings.join(' | ')}</span>
        </div>
      )}

      {/* 2. Main App Header */}
      <div className="app-header">
        <div className="header-inner">
          <div className="logo-section">
            <span className="logo-badge">Vision-Certi</span>
            <div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <h1 className="logo-title">원격판독 전자서명 플랫폼</h1>
                <InfoPopover
                  title="Vision-Certi 원격판독 전자서명 플랫폼"
                  buttonText="플랫폼 안내"
                  description="의료법 및 전자서명법을 준수하는 의료영상(DICOM/PACS) 판독문 전자서명, 위변조 방지 SHA-256 감사 체인 및 OpenBao 기반 키 관리 시스템입니다."
                  bullets={[
                    "의사 신원 확인과 판독문 서명키의 엄격한 분리 (이원화 아키텍처)",
                    "개인키/비밀번호 서버 미전송 원칙 준수 (클라이언트 사이드 검증)",
                    "불변 해시 체인 기반 실시간 전 행위 감사 로그 추적"
                  ]}
                  badge="의료영상 보안"
                />
              </div>
              <div className="logo-subtitle">
                의료영상 판독문 전자서명 및 감사 검증 콘솔 (v0.1.0-dev)
              </div>
            </div>
          </div>

          <div className="header-controls">
            <a href="#/login" className="header-pill-link" style={{ color: '#7dd3fc' }}>
              <LogIn size={13} />
              <span>개인 인증 로그인</span>
            </a>
            <a href="#/admin/login" className="header-pill-link" style={{ color: '#fbbf24' }}>
              관리자 로그인
            </a>
            <a href="#/signup" className="header-pill-link" style={{ color: '#5eead4' }}>
              <UserPlus size={13} />
              <span>개인·의료인 가입</span>
            </a>
            <a href="#/certificates/enroll" className="header-pill-link" style={{ color: '#34d399' }} data-testid="nav-certificate-enroll-btn">
              <KeyRound size={13} />
              <span>공동/금융/모바일 인증</span>
            </a>
            <a href="#/activate" className="header-pill-link" style={{ color: '#38bdf8' }} data-testid="nav-activate-btn">
              <UserCheck size={13} />
              <span>의사 계정 활성화</span>
            </a>
            <a href="#/doctors" className="header-pill-link" style={{ color: '#f59e0b' }} data-testid="nav-doctors-btn">
              <Users size={13} />
              <span>의사 관리 (Admin)</span>
            </a>

            {/* Staff OIDC Login Trigger / Status */}
            {staffProfile ? (
              <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                <span style={{ fontSize: '0.75rem', color: '#a7f3d0' }}>
                  Staff: <strong>{staffProfile.displayName}</strong> ({staffProfile.role})
                </span>
                <button
                  type="button"
                  data-testid="staff-logout-btn"
                  onClick={handleStaffLogout}
                  className="btn btn-secondary"
                  style={{
                    padding: '2px 8px',
                    fontSize: '0.7rem',
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: '4px',
                  }}
                >
                  <LogOut size={12} />
                  로그아웃
                </button>
              </div>
            ) : (
              <button
                type="button"
                data-testid="staff-oidc-login-btn"
                onClick={() => setShowStaffModal(true)}
                className="header-pill-link"
                style={{
                  background: 'transparent',
                  borderColor: '#0284c7',
                  color: '#38bdf8',
                  cursor: 'pointer',
                }}
              >
                <LogIn size={13} />
                <span>Staff OIDC 로그인</span>
              </button>
            )}
            {/* Dev-only Institution, Actor, and Mode Switchers (Hidden in production without demo flag) */}
            {(isDemoEnabled() || runtimeCapabilities?.profile !== 'prod') && (
              <>
                {/* Institution Switcher */}
                <div className="control-group">
                  <Building2 size={16} style={{ color: 'var(--teal-400)' }} />
                  <label htmlFor="tenant-select" className="control-label">
                    기관 선택:
                  </label>
                  <select
                    id="tenant-select"
                    className="control-select"
                    value={tenantId}
                    onChange={(e) => setTenantId(e.target.value as DevTenantId)}
                    aria-label="의료기관 선택"
                  >
                    <option value="demo-hospital-a">A 의료원 (본원 / demo-hospital-a)</option>
                    <option value="demo-hospital-b">B 병원 (분원 / demo-hospital-b)</option>
                  </select>
                </div>

                {/* Actor / Role Switcher */}
                <div className="control-group">
                  <UserCircle
                    size={16}
                    style={{ color: role === 'DOCTOR' ? '#38bdf8' : '#f59e0b' }}
                  />
                  <label htmlFor="actor-select" className="control-label">
                    접속 역할:
                  </label>
                  <select
                    id="actor-select"
                    className="control-select"
                    value={actorId}
                    onChange={(e) => setActorId(e.target.value as DevActorId)}
                    aria-label="사용자 역할 선택"
                  >
                    <option value="doctor-demo">김판독 의사 (DOCTOR / 서명권한 있음)</option>
                    <option value="admin-demo">박관리 관리자 (ADMIN / 서명버튼 제한)</option>
                  </select>
                </div>

                {/* API Mode Selector */}
                <div className="control-group">
                  {isMockMode ? (
                    <Database size={16} style={{ color: '#a78bfa' }} />
                  ) : (
                    <Server size={16} style={{ color: '#4ade80' }} />
                  )}
                  <label htmlFor="mode-select" className="control-label">
                    연동 모드:
                  </label>
                  <select
                    id="mode-select"
                    className="control-select"
                    value={isMockMode ? 'mock' : 'live'}
                    onChange={(e) => setIsMockMode(e.target.value === 'mock')}
                    aria-label="연동 모드 선택"
                  >
                    <option value="live">라이브 백엔드 (/v1 API Proxy)</option>
                    <option value="mock">명시적 모의 모드 (Client Mock)</option>
                  </select>
                </div>
              </>
            )}
          </div>
        </div>
      </div>

      {/* Staff OIDC Login Modal (PKCE Flow & In-Memory Bearer) */}
      {showStaffModal && (
        <div
          data-testid="staff-oidc-modal"
          style={{
            position: 'fixed',
            inset: 0,
            backgroundColor: 'rgba(0, 0, 0, 0.5)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1000,
          }}
        >
          <div
            style={{
              backgroundColor: '#fff',
              borderRadius: '8px',
              padding: '1.5rem',
              width: '100%',
              maxWidth: '480px',
              boxShadow: '0 20px 25px -5px rgba(0, 0, 0, 0.1)',
            }}
          >
            <h3 style={{ margin: '0 0 0.5rem 0', fontSize: '1.1rem', color: '#0f172a' }}>
              Staff OIDC 로그인 (Authorization Code + PKCE)
            </h3>
            <p style={{ margin: '0 0 1rem 0', fontSize: '0.8rem', color: '#64748b' }}>
              운영 환경 IdP와 연동하여 안전하게 로그인합니다. 토큰은 브라우저 메모리에만 유지되며 디스크나 URL에 저장되지 않습니다.
            </p>

            {staffLoginError && (
              <div
                data-testid="staff-login-error"
                style={{
                  padding: '0.5rem',
                  backgroundColor: '#fee2e2',
                  border: '1px solid #ef4444',
                  borderRadius: '4px',
                  color: '#991b1b',
                  fontSize: '0.8rem',
                  marginBottom: '1rem',
                }}
              >
                {staffLoginError}
              </div>
            )}

            {runtimeCapabilities?.staffAuth?.mode === 'OIDC' &&
            runtimeCapabilities.staffAuth.issuer &&
            runtimeCapabilities.staffAuth.clientId ? (
              <div style={{ marginBottom: '1rem', padding: '1rem', background: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: '6px' }}>
                <div style={{ fontSize: '0.8rem', color: '#334155', marginBottom: '8px' }}>
                  <div><strong>IdP Issuer:</strong> <code>{runtimeCapabilities.staffAuth.issuer}</code></div>
                  <div style={{ marginTop: '2px' }}><strong>Client ID:</strong> <code>{runtimeCapabilities.staffAuth.clientId}</code></div>
                  <div style={{ marginTop: '2px' }}><strong>보안 프로토콜:</strong> OAuth 2.0 PKCE (S256, State &amp; Nonce)</div>
                </div>

                <button
                  type="button"
                  data-testid="start-oidc-pkce-btn"
                  onClick={startOidcPkce}
                  disabled={staffLoginLoading}
                  style={{
                    width: '100%',
                    padding: '8px 16px',
                    fontSize: '0.9rem',
                    fontWeight: 600,
                    background: '#0284c7',
                    color: '#fff',
                    border: 'none',
                    borderRadius: '4px',
                    cursor: staffLoginLoading ? 'not-allowed' : 'pointer',
                    marginTop: '8px',
                  }}
                >
                  {staffLoginLoading ? 'OIDC 로그인 처리 중...' : '운영 IdP 로그인 시작 (PKCE 팝업)'}
                </button>
              </div>
            ) : (
              <div
                data-testid="oidc-unconfigured-notice"
                style={{
                  padding: '0.75rem',
                  backgroundColor: '#fef3c7',
                  border: '1px solid #f59e0b',
                  borderRadius: '4px',
                  color: '#92400e',
                  fontSize: '0.8rem',
                  marginBottom: '1rem',
                }}
              >
                <strong>운영 OIDC IdP 미구성:</strong> 현재 런타임 백엔드에 Staff OIDC IdP(Keycloak 등) 설정이 없습니다.
                운영 환경 배포 시 IdP issuer 및 clientId를 구성하십시오.
              </div>
            )}

            {/* Dev Fallback for testing/development */}
            {(isDemoEnabled() || runtimeCapabilities?.profile !== 'prod') && (
              <div style={{ marginTop: '1rem', borderTop: '1px dashed #cbd5e1', paddingTop: '0.75rem' }}>
                <button
                  type="button"
                  onClick={() => setShowDevTokenInput(!showDevTokenInput)}
                  style={{
                    background: 'none',
                    border: 'none',
                    color: '#64748b',
                    fontSize: '0.75rem',
                    cursor: 'pointer',
                    textDecoration: 'underline',
                    padding: 0,
                  }}
                >
                  {showDevTokenInput ? '▲ 개발용 토큰 직접 입력 접기' : '▼ 개발용 토큰 직접 입력 (Dev Only Fallback)'}
                </button>

                {showDevTokenInput && (
                  <form onSubmit={handleStaffLogin} style={{ marginTop: '8px' }}>
                    <textarea
                      data-testid="staff-jwt-input"
                      value={inputJwt}
                      onChange={(e) => setInputJwt(e.target.value)}
                      placeholder="eyJhbGciOiJSUzI1NiIs..."
                      rows={3}
                      style={{
                        width: '100%',
                        padding: '0.5rem',
                        fontSize: '0.75rem',
                        fontFamily: 'monospace',
                        border: '1px solid #cbd5e1',
                        borderRadius: '4px',
                      }}
                    />
                    <button
                      type="submit"
                      data-testid="submit-staff-jwt-btn"
                      disabled={staffLoginLoading || !inputJwt.trim()}
                      style={{
                        marginTop: '6px',
                        padding: '4px 10px',
                        fontSize: '0.75rem',
                        fontWeight: 600,
                        background: '#475569',
                        color: '#fff',
                        border: 'none',
                        borderRadius: '4px',
                        cursor: staffLoginLoading ? 'not-allowed' : 'pointer',
                      }}
                    >
                      {staffLoginLoading ? '검증 중...' : '개발 토큰 등록'}
                    </button>
                  </form>
                )}
              </div>
            )}

            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px', marginTop: '1rem' }}>
              <button
                type="button"
                onClick={() => setShowStaffModal(false)}
                style={{
                  padding: '6px 12px',
                  fontSize: '0.85rem',
                  background: '#f1f5f9',
                  border: '1px solid #cbd5e1',
                  borderRadius: '4px',
                  cursor: 'pointer',
                }}
              >
                닫기
              </button>
            </div>
          </div>
        </div>
      )}
    </header>
  );
};
