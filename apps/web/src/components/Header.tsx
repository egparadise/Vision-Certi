import React from 'react';
import { ShieldAlert, LogIn, UserPlus, KeyRound, LogOut } from 'lucide-react';
import { useApp } from '../context/AppContext';
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
    isMockMode,
    localAdminToken,
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
  let bannerText = '모의 환경 · 임상 사용 불가';
  let bannerBadge = isMockMode ? 'MOCK' : 'DEV';

  if (!isMockMode) {
    if (capabilitiesLoading) {
      bannerText = '서버 상태 확인 중';
      bannerBadge = '연결 중';
    } else if (capabilitiesError || !runtimeCapabilities) {
      bannerText = '서버 상태 확인 불가 · 임상 사용 중지';
      bannerBadge = '연결 확인 필요';
    } else if (runtimeCapabilities.clinicalUseAllowed) {
      bannerText = '임상 운영 중';
      bannerBadge = '운영';
    } else if (runtimeCapabilities.profile === 'prod' || runtimeCapabilities.signing?.backend === 'OPENBAO_TRANSIT') {
      bannerText = '임상 사용 전 검증 중';
      bannerBadge = `${runtimeCapabilities.profile.toUpperCase()} · ${runtimeCapabilities.persistence.mode}`;
    } else {
      bannerText = '개발 환경 · 임상 사용 불가';
      bannerBadge = `${runtimeCapabilities.profile.toUpperCase()} · ${runtimeCapabilities.persistence.mode}`;
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
        <InfoPopover
          title="서버 운영 상태"
          ariaLabel="서버 운영 상태 상세 보기"
          align="right"
          className="runtime-info"
          description={isMockMode
            ? '독립 모의 모드입니다. 이 화면의 기록은 임상 사용 증거가 아닙니다.'
            : runtimeCapabilities
              ? `서명 방식: ${runtimeCapabilities.signing.backend} · 저장소: ${runtimeCapabilities.persistence.mode} · 임상 사용: ${runtimeCapabilities.clinicalUseAllowed ? '허용' : '미허용'}`
              : '서버 구성을 확인할 수 없습니다. 상태가 확인되기 전까지 임상 사용을 중지하세요.'}
          bullets={runtimeCapabilities?.clinicalUseReason ? [`임상 사용 제한 사유: ${runtimeCapabilities.clinicalUseReason}`] : undefined}
        />
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
          <strong>보안 설정 확인 필요 · {runtimeCapabilities.warnings.length}건</strong>
          <InfoPopover
            title="보안 설정 상세"
            ariaLabel="보안 설정 경고 상세 보기"
            description="서버가 보고한 보안 설정 경고입니다. 운영 적용 전 각 항목을 확인하세요."
            bullets={runtimeCapabilities.warnings}
          />
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
                  description="의료영상(DICOM/PACS) 판독문 전자서명, SHA-256 감사 체인 및 OpenBao 기반 키 관리 기능을 제공합니다. 임상 사용 가능 여부는 서버 상태를 확인해 주세요."
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
              <span>공동인증서 로그인</span>
            </a>
            <a href="#/physician/account" className="header-pill-link" style={{ color: '#5eead4' }}>
              <UserPlus size={13} />
              <span>의사 가입·인증서 등록</span>
            </a>
            <a href={localAdminToken || staffProfile?.role === 'ADMIN' ? '#/admin' : '#/admin/login'} className="header-pill-link" style={{ color: '#fbbf24' }} data-testid="nav-certificate-dashboard-btn">
              <KeyRound size={13} />
              <span>인증서별 판독 현황</span>
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
            ) : runtimeCapabilities?.staffAuth?.mode === 'OIDC' ? (
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
            ) : null}
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
