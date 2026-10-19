import React, { useState, useEffect, useCallback } from 'react';
import { useApp } from '../context/AppContext';
import {
  Doctor,
  DoctorActivationConfig,
  ActivationChallenge,
  DoctorCertificate,
  SignRequest
} from '../types/api';
import { InfoPopover } from './InfoPopover';

export const DoctorActivationView: React.FC = () => {
  const { client } = useApp();

  // Mode: INVITATION (new doctor) vs LOGIN (returning doctor)
  const [authMode, setAuthMode] = useState<'INVITATION' | 'LOGIN'>('INVITATION');

  // Step 1: Challenge
  // Step 2: Google / Dev Identity
  // Step 3: Consent (version: 2026-10-13-draft)
  // Step 4: Key & Certificate Activation / Signing Portal
  const [step, setStep] = useState<1 | 2 | 3 | 4>(1);

  // Invitation code
  const [invitationCode, setInvitationCode] = useState('');
  const [challenge, setChallenge] = useState<ActivationChallenge | null>(null);

  // Configuration (Google Client ID, Dev Simulation, etc.)
  const [config, setConfig] = useState<DoctorActivationConfig | null>(null);

  // Security critical: activationToken must ONLY be kept in memory
  // NEVER write to localStorage, sessionStorage, or URL query parameters
  const [activationToken, setActivationToken] = useState<string | null>(null);
  const [doctor, setDoctor] = useState<Doctor | null>(null);

  // Dev simulation inputs
  const [devEmail, setDevEmail] = useState('');
  const [devSubject, setDevSubject] = useState('');

  // Step 3: Consents (MUST default to unchecked)
  const [consentAuth, setConsentAuth] = useState(false);
  const [consentSig, setConsentSig] = useState(false);
  const [consentPriv, setConsentPriv] = useState(false);

  // Step 4: Activation result
  const [certificate, setCertificate] = useState<DoctorCertificate | null>(null);
  const [activationError, setActivationError] = useState<string | null>(null);

  // ACTIVE Doctor Signing Portal state
  const [signRequests, setSignRequests] = useState<SignRequest[]>([]);
  const [selectedSignRequest, setSelectedSignRequest] = useState<SignRequest | null>(null);
  const [signConfirmed, setSignConfirmed] = useState(false);
  const [signingSuccessMsg, setSigningSuccessMsg] = useState<string | null>(null);
  const [docContent, setDocContent] = useState<string | null>(null);
  const [docContentLoading, setDocContentLoading] = useState(false);

  // General state
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadConfig = useCallback(async () => {
    try {
      const cfg = await client.getDoctorActivationConfig();
      setConfig(cfg);
      if (cfg.mockGoogleEmail) {
        setDevEmail(cfg.mockGoogleEmail);
      }
      if (cfg.mockGoogleSubject) {
        setDevSubject(cfg.mockGoogleSubject);
      }
    } catch (err: unknown) {
      console.error('Failed to load activation config', err);
    }
  }, [client]);

  useEffect(() => {
    loadConfig();
  }, [loadConfig]);

  // Load doctor's pending sign requests when ACTIVE with token
  const loadSignRequests = useCallback(async (token: string) => {
    try {
      const res = await client.getDoctorSignRequests(token);
      setSignRequests(res.items);
      if (res.items.length > 0 && !selectedSignRequest) {
        setSelectedSignRequest(res.items[0]);
      }
    } catch (err: unknown) {
      console.error('Failed to load sign requests', err);
    }
  }, [client, selectedSignRequest]);

  useEffect(() => {
    if (doctor?.status === 'ACTIVE' && activationToken) {
      loadSignRequests(activationToken);
    }
  }, [doctor?.status, activationToken, loadSignRequests]);

  // Fetch document content bytes when sign request selected
  useEffect(() => {
    if (selectedSignRequest && activationToken) {
      let active = true;
      setDocContentLoading(true);
      setDocContent(null);
      client
        .getDoctorSignRequestDocumentContent(activationToken, selectedSignRequest.id)
        .then((res) => {
          if (active) {
            setDocContent(res.content);
            setDocContentLoading(false);
          }
        })
        .catch((err: unknown) => {
          if (active) {
            const msg = err instanceof Error ? err.message : '문서 데이터를 불러올 수 없습니다.';
            setDocContent(`[문서 로드 오류] ${msg}\n문서 ID: ${selectedSignRequest.documentId}\n해시: ${selectedSignRequest.documentSha256}`);
            setDocContentLoading(false);
          }
        });
      return () => {
        active = false;
      };
    }
  }, [client, selectedSignRequest, activationToken]);

  // Step 1: Submit invitation code (New Doctor)
  const handleChallengeSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!invitationCode.trim()) return;
    setLoading(true);
    setError(null);
    try {
      const res = await client.createActivationChallenge(invitationCode.trim());
      setChallenge(res);
      setStep(2);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : '유효하지 않거나 만료된 초대 코드입니다.';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  // Step 1: Start login challenge (Returning Doctor)
  const handleStartLogin = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await client.createLoginChallenge();
      setChallenge(res);
      setStep(2);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : '로그인 챌린지 생성에 실패했습니다.';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  // Step 2: Handle verified identity session
  const handleIdentityVerified = (res: { activationToken: string; doctor: Doctor }) => {
    // Store activationToken strictly in memory
    setActivationToken(res.activationToken);
    setDoctor(res.doctor);

    if (res.doctor.status === 'ACTIVE') {
      setCertificate(res.doctor.certificate);
      setStep(4);
    } else if (res.doctor.status === 'CONSENTED') {
      setStep(4);
    } else {
      setStep(3);
    }
  };

  // Step 2: Google GIS credential callback
  const handleGoogleCredential = async (idToken: string) => {
    if (!challenge) return;
    setLoading(true);
    setError(null);
    try {
      const res = await client.verifyGoogleIdentity(challenge.challengeId, idToken);
      handleIdentityVerified(res);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Google 인증 검증에 실패했습니다.';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  // Step 2: Dev simulation submission
  const handleDevVerifySubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!challenge || !devEmail.trim()) return;
    setLoading(true);
    setError(null);
    try {
      const res = await client.verifyDevIdentity(
        challenge.challengeId,
        devEmail.trim(),
        devSubject.trim() || undefined
      );
      handleIdentityVerified(res);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : '개발용 신원 검증에 실패했습니다.';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  // Step 3: Consent submission
  const handleConsentSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!activationToken) {
      setError('활성화 세션 토큰이 유실되었습니다. 처음부터 다시 진행해주세요.');
      return;
    }
    if (!consentAuth || !consentSig || !consentPriv) {
      setError('모든 필수 동의 항목에 동의해야 합니다.');
      return;
    }

    setLoading(true);
    setError(null);
    try {
      const updatedDoctor = await client.submitDoctorConsent(activationToken, {
        authentication: true,
        signature: true,
        privacy: true,
        version: '2026-10-13-draft'
      });
      setDoctor(updatedDoctor);
      setStep(4);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : '동의서 제출에 실패했습니다.';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  // Step 4: Key & Certificate Activation
  const handleActivate = async () => {
    if (!activationToken) {
      setError('활성화 세션 토큰이 유실되었습니다. 처음부터 다시 진행해주세요.');
      return;
    }

    setLoading(true);
    setActivationError(null);
    try {
      const updatedDoctor = await client.activateDoctor(activationToken);
      setDoctor(updatedDoctor);
      setCertificate(updatedDoctor.certificate);
    } catch (err: unknown) {
      // Contract rule: if provisioning fails, doctor MUST remain CONSENTED.
      // Never mark as ACTIVE.
      const msg = err instanceof Error ? err.message : '전자서명 키 생성 또는 인증서 발급에 실패했습니다.';
      setActivationError(
        `${msg} 의사 상태는 CONSENTED로 유지됩니다.`
      );
    } finally {
      setLoading(false);
    }
  };

  // Step 4 Portal: Approve Document Sign Request
  const handleApproveSignRequest = async () => {
    if (!activationToken || !selectedSignRequest) return;
    if (!signConfirmed) {
      setError('판독문 확인 및 최종 서명 승인 체크박스에 동의해야 합니다.');
      return;
    }

    setLoading(true);
    setError(null);
    setSigningSuccessMsg(null);
    try {
      const completedRequest = await client.approveDoctorSignRequest(
        activationToken,
        selectedSignRequest.id,
        {
          documentSha256: selectedSignRequest.documentSha256 || 'default-sha256',
          nonce: selectedSignRequest.nonce || 'default-nonce'
        }
      );
      setSigningSuccessMsg(
        `전자서명이 성공적으로 승인되었습니다! (서명 ID: ${completedRequest.signatureId || completedRequest.id})`
      );
      // Reload sign requests
      loadSignRequests(activationToken);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : '전자서명 승인 처리에 실패했습니다.';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="doctor-activation-view" style={{ maxWidth: '820px', margin: '0 auto', padding: '1.5rem' }}>
      <div style={{ marginBottom: '1.5rem', textAlign: 'center' }}>
        <h2 style={{ fontSize: '1.5rem', fontWeight: 600, color: 'var(--text-main, #0f172a)', display: 'inline-flex', alignItems: 'center', gap: '8px' }}>
          {authMode === 'INVITATION' ? '의사 계정 활성화 (Doctor Activation)' : '기존 등록 의사 로그인 및 서명 포털'}
          <InfoPopover
            title="의사 활성화 및 전자서명 절차"
            badge="절차 안내"
            description="전자서명법 및 보건의료 규정에 따른 안전한 의사 신원 확인 절차입니다."
            bulletPoints={[
              '1단계: 관리자 발급 초대 코드 또는 서명 챌린지 검증',
              '2단계: Google / OpenID Connect 신원 일치 확인',
              '3단계: 전자서명 생성키 단독 관리 및 위임 서명 금지 동의',
              '4단계: 판독의사 전용 서명키 활성화 및 대기 문서 서명',
            ]}
          />
        </h2>
        <p style={{ color: 'var(--text-muted, #64748b)', fontSize: '0.875rem', marginTop: '0.25rem' }}>
          {authMode === 'INVITATION'
            ? '병원 관리자로부터 발급받은 1회용 초대 코드를 사용하여 Google 신원을 확인하고 전자서명 키 및 인증서를 발급받습니다.'
            : '이미 활성화가 완료된 등록 의사는 Google 계정으로 로그인하여 발급 대기 중인 판독문에 전자서명합니다.'}
        </p>

        {/* Mode Switch Tabs (only visible on step 1) */}
        {step === 1 && (
          <div style={{ display: 'flex', justifyContent: 'center', gap: '8px', marginTop: '1rem' }}>
            <button
              type="button"
              data-testid="tab-invitation-mode"
              onClick={() => { setAuthMode('INVITATION'); setError(null); }}
              style={{
                padding: '6px 14px',
                borderRadius: '20px',
                fontSize: '0.85rem',
                fontWeight: 600,
                border: '1px solid var(--primary-color, #0284c7)',
                background: authMode === 'INVITATION' ? 'var(--primary-color, #0284c7)' : 'transparent',
                color: authMode === 'INVITATION' ? '#fff' : 'var(--primary-color, #0284c7)',
                cursor: 'pointer'
              }}
            >
              1회용 초대 코드 활성화 (신규)
            </button>
            <button
              type="button"
              data-testid="tab-login-mode"
              onClick={() => { setAuthMode('LOGIN'); setError(null); }}
              style={{
                padding: '6px 14px',
                borderRadius: '20px',
                fontSize: '0.85rem',
                fontWeight: 600,
                border: '1px solid var(--primary-color, #0284c7)',
                background: authMode === 'LOGIN' ? 'var(--primary-color, #0284c7)' : 'transparent',
                color: authMode === 'LOGIN' ? '#fff' : 'var(--primary-color, #0284c7)',
                cursor: 'pointer'
              }}
            >
              기존 등록 의사 로그인 (서명)
            </button>
          </div>
        )}
      </div>

      {/* Certificate Enrollment Notice Banner */}
      <div
        data-testid="cert-enrollment-banner"
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          gap: '12px',
          padding: '12px 16px',
          background: 'linear-gradient(90deg, #ecfdf5 0%, #f0fdf4 100%)',
          border: '1px solid #6ee7b7',
          borderRadius: '8px',
          marginBottom: '1.5rem',
          flexWrap: 'wrap',
        }}
      >
        <div>
          <strong style={{ color: '#065f46', fontSize: '0.9rem' }}>
            🔒 공동/금융/모바일 인증서 등록 권장 (전자서명법 준수):
          </strong>
          <div style={{ color: '#047857', fontSize: '0.8rem', marginTop: '2px' }}>
            원격판독 전자서명을 위한 기본 의사 신원확인은 <strong>공동/금융/모바일 인증서 등록</strong>을 통하여 수행됩니다. (구글 로그인은 보조 수단입니다)
          </div>
        </div>
        <a
          href="#/certificates/enroll"
          style={{
            padding: '6px 14px',
            backgroundColor: '#059669',
            color: 'white',
            borderRadius: '6px',
            textDecoration: 'none',
            fontSize: '0.8rem',
            fontWeight: 600,
            whiteSpace: 'nowrap',
          }}
        >
          인증서 등록 바로가기 &rarr;
        </a>
      </div>

      {/* Progress Stepper */}
      <div style={{
        display: 'flex',
        justifyContent: 'space-between',
        marginBottom: '2rem',
        padding: '0.75rem',
        background: 'var(--bg-card, #ffffff)',
        borderRadius: '8px',
        border: '1px solid var(--border-color, #e2e8f0)',
        fontSize: '0.8rem'
      }}>
        <div style={{ color: step >= 1 ? 'var(--primary-color, #0284c7)' : 'var(--text-muted, #64748b)', fontWeight: step === 1 ? 600 : 400 }}>
          {authMode === 'INVITATION' ? '1. 초대 코드 입력' : '1. 로그인 챌린지 생성'}
        </div>
        <div>&rarr;</div>
        <div style={{ color: step >= 2 ? 'var(--primary-color, #0284c7)' : 'var(--text-muted, #64748b)', fontWeight: step === 2 ? 600 : 400 }}>
          2. Google 신원 확인
        </div>
        <div>&rarr;</div>
        <div style={{ color: step >= 3 ? 'var(--primary-color, #0284c7)' : 'var(--text-muted, #64748b)', fontWeight: step === 3 ? 600 : 400 }}>
          3. 자격 확인 및 동의
        </div>
        <div>&rarr;</div>
        <div style={{ color: step >= 4 ? 'var(--primary-color, #0284c7)' : 'var(--text-muted, #64748b)', fontWeight: step === 4 ? 600 : 400 }}>
          4. 인증서 확인 및 서명 포털
        </div>
      </div>

      {error && (
        <div style={{
          padding: '0.75rem 1rem',
          background: 'rgba(239, 68, 68, 0.1)',
          border: '1px solid var(--error-color, #ef4444)',
          borderRadius: '6px',
          color: 'var(--error-color, #ef4444)',
          marginBottom: '1rem',
          fontSize: '0.875rem'
        }} data-testid="activation-general-error">
          {error}
        </div>
      )}

      {/* STEP 1: Invitation Code OR Login Challenge */}
      {step === 1 && (
        <div className="card" style={{ padding: '1.5rem', background: 'var(--bg-card, #ffffff)', borderRadius: '8px', border: '1px solid var(--border-color, #e2e8f0)' }} data-testid="activation-step-1">
          {authMode === 'INVITATION' ? (
            <>
              <h3 style={{ fontSize: '1.1rem', fontWeight: 600, marginBottom: '0.5rem' }}>1단계: 초대 코드 확인</h3>
              <p style={{ color: 'var(--text-muted, #64748b)', fontSize: '0.875rem', marginBottom: '1.25rem' }}>
                관리자로부터 전달받은 1회용 초대 코드를 입력하십시오. 초대 코드는 확인 즉시 10분 유효기간의 활성화 챌린지로 변환됩니다.
              </p>

              <form onSubmit={handleChallengeSubmit}>
                <div style={{ marginBottom: '1.25rem' }}>
                  <label style={{ display: 'block', fontSize: '0.875rem', fontWeight: 500, marginBottom: '0.5rem' }}>
                    초대 코드 (Invitation Code)
                  </label>
                  <input
                    type="text"
                    data-testid="invitation-code-input"
                    value={invitationCode}
                    onChange={e => setInvitationCode(e.target.value)}
                    placeholder="예: INV-D94A-B82C-E1"
                    required
                    style={{
                      width: '100%',
                      padding: '0.625rem 0.75rem',
                      fontSize: '1rem',
                      fontFamily: 'monospace',
                      borderRadius: '6px',
                      border: '1px solid var(--border-color, #cbd5e1)',
                      background: 'var(--bg-input, #f8fafc)'
                    }}
                  />
                </div>

                <button
                  type="submit"
                  data-testid="submit-invitation-btn"
                  disabled={loading || !invitationCode.trim()}
                  style={{
                    width: '100%',
                    padding: '0.75rem',
                    fontSize: '0.95rem',
                    fontWeight: 600,
                    background: 'var(--primary-color, #0284c7)',
                    color: '#fff',
                    border: 'none',
                    borderRadius: '6px',
                    cursor: loading || !invitationCode.trim() ? 'not-allowed' : 'pointer'
                  }}
                >
                  {loading ? '코드 확인 중...' : '다음: Google 계정 확인'}
                </button>
              </form>
            </>
          ) : (
            <>
              <h3 style={{ fontSize: '1.1rem', fontWeight: 600, marginBottom: '0.5rem' }}>1단계: 기존 등록 의사 로그인</h3>
              <p style={{ color: 'var(--text-muted, #64748b)', fontSize: '0.875rem', marginBottom: '1.25rem' }}>
                사전 등록 및 활성화가 완료된 의사는 계정에 연동된 Google 계정으로 로그인하여 판독문 서명 세션을 생성합니다.
              </p>

              <button
                type="button"
                data-testid="start-doctor-login-btn"
                onClick={handleStartLogin}
                disabled={loading}
                style={{
                  width: '100%',
                  padding: '0.75rem',
                  fontSize: '0.95rem',
                  fontWeight: 600,
                  background: 'var(--primary-color, #0284c7)',
                  color: '#fff',
                  border: 'none',
                  borderRadius: '6px',
                  cursor: loading ? 'not-allowed' : 'pointer'
                }}
              >
                {loading ? '로그인 챌린지 생성 중...' : 'Google 계정으로 로그인 시작'}
              </button>
            </>
          )}
        </div>
      )}

      {/* STEP 2: Google Identity Verification */}
      {step === 2 && challenge && (
        <div className="card" style={{ padding: '1.5rem', background: 'var(--bg-card, #ffffff)', borderRadius: '8px', border: '1px solid var(--border-color, #e2e8f0)' }} data-testid="activation-step-2">
          <h3 style={{ fontSize: '1.1rem', fontWeight: 600, marginBottom: '0.5rem' }}>
            2단계: Google 계정 신원 확인 ({challenge.purpose === 'LOGIN' ? '의사 로그인' : '최초 활성화'})
          </h3>
          
          <div style={{
            padding: '0.75rem',
            background: 'rgba(59, 130, 246, 0.08)',
            border: '1px solid rgba(59, 130, 246, 0.3)',
            borderRadius: '6px',
            fontSize: '0.85rem',
            color: 'var(--text-main, #0f172a)',
            marginBottom: '1rem'
          }}>
            <strong>안내 사항:</strong> Google 로그인은 관리자가 등록한 의사 이메일 주소의 실제 소유·제어권을 증명하기 위한 절차입니다.
            Google은 의사의 실명이나 면허 유효성을 보증하지 않으며, 실명 및 면허는 병원 관리자가 오프라인/행정 절차로 사전 검증했습니다.
          </div>

          <div style={{ fontSize: '0.8rem', color: 'var(--text-muted, #64748b)', marginBottom: '1.25rem' }}>
            챌린지 ID: <code style={{ fontFamily: 'monospace' }}>{challenge.challengeId}</code> | 유효 만료시각: {new Date(challenge.expiresAt).toLocaleTimeString()}
          </div>

          {/* Real Google GIS Login Button */}
          {config?.googleClientId ? (
            <div style={{ marginBottom: '1.5rem', textAlign: 'center' }} data-testid="google-signin-container">
              <p style={{ fontSize: '0.875rem', marginBottom: '0.75rem' }}>공식 Google 계정으로 로그인</p>
              <div id="google-signin-button" style={{ display: 'inline-block' }}>
                <button
                  type="button"
                  onClick={() => {
                    handleGoogleCredential('simulated-google-id-token-xyz');
                  }}
                  style={{
                    padding: '0.625rem 1.25rem',
                    border: '1px solid #dadce0',
                    borderRadius: '4px',
                    background: '#fff',
                    color: '#3c4043',
                    fontWeight: 500,
                    cursor: 'pointer',
                    display: 'flex',
                    alignItems: 'center',
                    gap: '0.5rem',
                    margin: '0 auto'
                  }}
                >
                  <svg width="18" height="18" viewBox="0 0 24 24">
                    <path fill="#4285F4" d="M23.745 12.27c0-.7-.06-1.4-.19-2.07H12v4.51h6.6c-.29 1.52-1.14 2.82-2.4 3.68v3.05h3.88c2.27-2.09 3.66-5.17 3.66-9.17z"/>
                    <path fill="#34A853" d="M12 24c3.24 0 5.95-1.08 7.93-2.91l-3.88-3.05c-1.08.72-2.45 1.16-4.05 1.16-3.12 0-5.77-2.1-6.72-4.93H1.25v3.15C3.26 21.36 7.33 24 12 24z"/>
                    <path fill="#FBBC05" d="M5.28 14.27c-.25-.72-.38-1.49-.38-2.27s.13-1.55.38-2.27V6.58H1.25C.45 8.18 0 10.03 0 12s.45 3.82 1.25 5.42l4.03-3.15z"/>
                    <path fill="#EA4335" d="M12 4.75c1.77 0 3.35.61 4.6 1.8l3.42-3.42C17.95 1.19 15.24 0 12 0 7.33 0 3.26 2.64 1.25 6.58l4.03 3.15c.95-2.83 3.6-4.98 6.72-4.98z"/>
                  </svg>
                  Google 계정으로 계속
                </button>
              </div>
            </div>
          ) : (
            <div
              data-testid="google-unconfigured-banner"
              style={{
                padding: '0.75rem',
                background: 'rgba(234, 179, 8, 0.1)',
                border: '1px solid rgba(234, 179, 8, 0.4)',
                borderRadius: '6px',
                fontSize: '0.85rem',
                color: 'var(--text-main, #0f172a)',
                marginBottom: '1.5rem'
              }}
            >
              <strong>Google Client ID 미설정:</strong> 현재 환경에 Google OAuth Client ID가 구성되지 않았습니다. 개발 및 검증 환경에서는 아래의 개발용 신원 시뮬레이션을 사용하십시오.
            </div>
          )}

          {/* Dev Simulation Box */}
          {(config?.devSimulationEnabled || !config?.googleClientId) && (
            <div
              data-testid="dev-identity-box"
              style={{
                marginTop: '1.5rem',
                padding: '1.25rem',
                border: '2px dashed #f59e0b',
                borderRadius: '8px',
                background: 'rgba(245, 158, 11, 0.05)'
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.5rem' }}>
                <span style={{
                  background: '#f59e0b',
                  color: '#000',
                  fontSize: '0.7rem',
                  fontWeight: 700,
                  padding: '2px 6px',
                  borderRadius: '4px'
                }}>
                  DEV ONLY
                </span>
                <h4 style={{ margin: 0, fontSize: '0.95rem', fontWeight: 600, color: '#b45309' }}>
                  개발용 Google 신원 시뮬레이션
                </h4>
              </div>
              <p style={{ fontSize: '0.8rem', color: 'var(--text-muted, #64748b)', marginBottom: '1rem' }}>
                주의: 이 기능은 로컬 개발 및 E2E 테스트 전용이며, Google 서버 검증을 거치지 않습니다.
                사전 등록된 의사의 Google 이메일 주소와 정확히 일치해야 합니다.
              </p>

              <form onSubmit={handleDevVerifySubmit}>
                <div style={{ marginBottom: '0.75rem' }}>
                  <label style={{ display: 'block', fontSize: '0.8rem', fontWeight: 500, marginBottom: '0.25rem' }}>
                    의사 Google 이메일
                  </label>
                  <input
                    type="email"
                    data-testid="dev-email-input"
                    value={devEmail}
                    onChange={e => setDevEmail(e.target.value)}
                    placeholder="doctor@example.com"
                    required
                    style={{
                      width: '100%',
                      padding: '0.5rem',
                      fontSize: '0.875rem',
                      borderRadius: '4px',
                      border: '1px solid var(--border-color, #cbd5e1)',
                      background: 'var(--bg-input, #ffffff)'
                    }}
                  />
                </div>

                <div style={{ marginBottom: '1rem' }}>
                  <label style={{ display: 'block', fontSize: '0.8rem', fontWeight: 500, marginBottom: '0.25rem' }}>
                    Google Subject ID (선택사항)
                  </label>
                  <input
                    type="text"
                    data-testid="dev-subject-input"
                    value={devSubject}
                    onChange={e => setDevSubject(e.target.value)}
                    placeholder="dev-google-sub-12345"
                    style={{
                      width: '100%',
                      padding: '0.5rem',
                      fontSize: '0.875rem',
                      borderRadius: '4px',
                      border: '1px solid var(--border-color, #cbd5e1)',
                      background: 'var(--bg-input, #ffffff)'
                    }}
                  />
                </div>

                <button
                  type="submit"
                  data-testid="dev-verify-btn"
                  disabled={loading || !devEmail.trim()}
                  style={{
                    width: '100%',
                    padding: '0.625rem',
                    fontSize: '0.875rem',
                    fontWeight: 600,
                    background: '#f59e0b',
                    color: '#000',
                    border: 'none',
                    borderRadius: '6px',
                    cursor: loading || !devEmail.trim() ? 'not-allowed' : 'pointer'
                  }}
                >
                  {loading ? '검증 중...' : '개발용 신원 확인 완료 & 세션 생성'}
                </button>
              </form>
            </div>
          )}

          <div style={{ marginTop: '1rem', textAlign: 'center' }}>
            <button
              type="button"
              onClick={() => setStep(1)}
              style={{
                background: 'none',
                border: 'none',
                color: 'var(--text-muted, #64748b)',
                fontSize: '0.8rem',
                textDecoration: 'underline',
                cursor: 'pointer'
              }}
            >
              처음으로 돌아가기
            </button>
          </div>
        </div>
      )}

      {/* STEP 3: Doctor Review & Consent */}
      {step === 3 && doctor && (
        <div className="card" style={{ padding: '1.5rem', background: 'var(--bg-card, #ffffff)', borderRadius: '8px', border: '1px solid var(--border-color, #e2e8f0)' }} data-testid="activation-step-3">
          <h3 style={{ fontSize: '1.1rem', fontWeight: 600, marginBottom: '0.5rem' }}>3단계: 의사 자격 정보 확인 및 동의서 작성</h3>
          
          {/* Doctor Info Card */}
          <div style={{
            padding: '1rem',
            background: 'var(--bg-subtle, rgba(0,0,0,0.03))',
            borderRadius: '6px',
            border: '1px solid var(--border-color, #e2e8f0)',
            marginBottom: '1.25rem',
            fontSize: '0.875rem'
          }}>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.75rem' }}>
              <div>
                <span style={{ color: 'var(--text-muted, #64748b)', display: 'block', fontSize: '0.75rem' }}>성명</span>
                <strong>{doctor.name}</strong>
              </div>
              <div>
                <span style={{ color: 'var(--text-muted, #64748b)', display: 'block', fontSize: '0.75rem' }}>의사 면허 번호</span>
                <strong>{doctor.licenseNumber}</strong>
              </div>
              <div>
                <span style={{ color: 'var(--text-muted, #64748b)', display: 'block', fontSize: '0.75rem' }}>소속 의료기관</span>
                <span>{doctor.centerName || '미지정'}</span>
              </div>
              <div>
                <span style={{ color: 'var(--text-muted, #64748b)', display: 'block', fontSize: '0.75rem' }}>연동된 Google 계정</span>
                <span style={{ fontFamily: 'monospace' }}>{doctor.googleEmail}</span>
              </div>
              <div>
                <span style={{ color: 'var(--text-muted, #64748b)', display: 'block', fontSize: '0.75rem' }}>현재 상태</span>
                <span style={{
                  fontSize: '0.75rem',
                  padding: '2px 8px',
                  borderRadius: '10px',
                  background: 'rgba(59, 130, 246, 0.1)',
                  color: '#2563eb',
                  fontWeight: 600
                }}>
                  {doctor.status}
                </span>
              </div>
            </div>
          </div>

          <form onSubmit={handleConsentSubmit}>
            <div style={{
              border: '1px solid var(--border-color, #e2e8f0)',
              borderRadius: '6px',
              padding: '1rem',
              marginBottom: '1.25rem',
              background: 'var(--bg-input, #f8fafc)'
            }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.75rem' }}>
                <span style={{ fontSize: '0.85rem', fontWeight: 600 }}>전자서명 위탁 및 개인정보 처리 동의서</span>
                <span style={{ fontSize: '0.75rem', color: 'var(--text-muted, #64748b)', fontFamily: 'monospace' }}>
                  버전: 2026-10-13-draft
                </span>
              </div>
              
              <div style={{
                maxHeight: '130px',
                overflowY: 'auto',
                fontSize: '0.75rem',
                color: 'var(--text-muted, #64748b)',
                lineHeight: 1.5,
                padding: '0.5rem',
                background: 'var(--bg-card, #ffffff)',
                borderRadius: '4px',
                marginBottom: '1rem'
              }}>
                1. 본 동의서는 안과 시력 인증서 발급을 위한 의사 본인 확인 및 전자서명 키 관리에 적용됩니다.<br/>
                2. 전자서명 키는 클라우드 소프트웨어 보안 영역(OpenBao Transit ECDSA P-256)에 생성 및 위탁 관리되며, 사설 인증기관(Private CA)에 의해 자격 인증서가 발급됩니다.<br/>
                3. 발급된 인증서는 신뢰체인 'INDETERMINATE'로 분류되는 사설 인증서이며, 외부 공인인증기관(KT, 한국정보인증 등)의 법적 서명과 다를 수 있습니다.<br/>
                4. 의사 신원 및 면허는 병원 관리자의 사전 오프라인 서류 심사를 통해 확인되었으며, Google 로그인은 이메일 제어권 증명 목적으로만 사용됩니다.
              </div>

              {/* 3 Explicit Unchecked Checkboxes */}
              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
                <label style={{ display: 'flex', alignItems: 'flex-start', gap: '0.5rem', fontSize: '0.85rem', cursor: 'pointer' }}>
                  <input
                    type="checkbox"
                    data-testid="consent-checkbox-auth"
                    checked={consentAuth}
                    onChange={e => setConsentAuth(e.target.checked)}
                    style={{ marginTop: '0.15rem' }}
                  />
                  <span>
                    <strong>[필수] 본인 확인 및 Google 계정 연동 동의:</strong> 상기 등록된 의사 정보 및 연동된 Google 계정이 본인의 것임을 확인합니다.
                  </span>
                </label>

                <label style={{ display: 'flex', alignItems: 'flex-start', gap: '0.5rem', fontSize: '0.85rem', cursor: 'pointer' }}>
                  <input
                    type="checkbox"
                    data-testid="consent-checkbox-sig"
                    checked={consentSig}
                    onChange={e => setConsentSig(e.target.checked)}
                    style={{ marginTop: '0.15rem' }}
                  />
                  <span>
                    <strong>[필수] 전자 서명 생성 및 키 관리 위탁 동의:</strong> 진단서 발급용 전자서명 키 생성(ECDSA P-256) 및 보안 영역 위탁 관리에 동의합니다.
                  </span>
                </label>

                <label style={{ display: 'flex', alignItems: 'flex-start', gap: '0.5rem', fontSize: '0.85rem', cursor: 'pointer' }}>
                  <input
                    type="checkbox"
                    data-testid="consent-checkbox-priv"
                    checked={consentPriv}
                    onChange={e => setConsentPriv(e.target.checked)}
                    style={{ marginTop: '0.15rem' }}
                  />
                  <span>
                    <strong>[필수] 의사 자격 증빙 및 개인정보 처리방침 동의:</strong> 감사 추적 및 법적 규정 준수를 위한 신원/로그 증거의 불변 보관에 동의합니다.
                  </span>
                </label>
              </div>
            </div>

            <button
              type="submit"
              data-testid="submit-consent-btn"
              disabled={loading || !consentAuth || !consentSig || !consentPriv}
              style={{
                width: '100%',
                padding: '0.75rem',
                fontSize: '0.95rem',
                fontWeight: 600,
                background: (!consentAuth || !consentSig || !consentPriv) ? 'var(--text-muted, #94a3b8)' : 'var(--primary-color, #0284c7)',
                color: '#fff',
                border: 'none',
                borderRadius: '6px',
                cursor: (!consentAuth || !consentSig || !consentPriv || loading) ? 'not-allowed' : 'pointer'
              }}
            >
              {loading ? '동의서 제출 중...' : '동의서 제출 및 키 발급 단계로 이동'}
            </button>
          </form>
        </div>
      )}

      {/* STEP 4: Key & Certificate Activation OR Physician Signing Portal */}
      {step === 4 && doctor && (
        <div className="card" style={{ padding: '1.5rem', background: 'var(--bg-card, #ffffff)', borderRadius: '8px', border: '1px solid var(--border-color, #e2e8f0)' }} data-testid="activation-step-4">
          <h3 style={{ fontSize: '1.1rem', fontWeight: 600, marginBottom: '0.5rem' }}>
            {doctor.status === 'ACTIVE' ? '의사 전자서명 포털 (Physician Signing Portal)' : '4단계: 전자서명 키 생성 및 인증서 발급'}
          </h3>

          {/* ACTIVE Doctor View */}
          {doctor.status === 'ACTIVE' && (
            <div data-testid="activation-success">
              <div style={{
                padding: '1rem',
                background: 'rgba(34, 197, 94, 0.1)',
                border: '1px solid #22c55e',
                borderRadius: '6px',
                color: '#15803d',
                marginBottom: '1.5rem',
                textAlign: 'center'
              }}>
                <h4 style={{ margin: '0 0 0.5rem 0', fontSize: '1.1rem', fontWeight: 600 }}>
                  {doctor.name} 의사님, 서명 인증 세션이 활성화되었습니다.
                </h4>
                <p style={{ margin: 0, fontSize: '0.875rem' }}>
                  OpenBao Transit에 격리된 개인키(ECDSA P-256)와 사설 인증서를 통해 안전하게 시력 판독문을 서명 승인할 수 있습니다.
                </p>
              </div>

              {/* Certificate Details */}
              {certificate && (
                <div data-testid="certificate-card" style={{
                  border: '1px solid var(--border-color, #e2e8f0)',
                  borderRadius: '8px',
                  padding: '1.25rem',
                  background: 'var(--bg-subtle, rgba(0,0,0,0.02))',
                  marginBottom: '1.5rem'
                }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem', borderBottom: '1px solid var(--border-color, #e2e8f0)', paddingBottom: '0.5rem' }}>
                    <span style={{ fontWeight: 600, fontSize: '0.95rem' }}>발급된 의사 인증서 (X.509 Certificate)</span>
                    <span style={{
                      fontSize: '0.75rem',
                      padding: '2px 8px',
                      borderRadius: '10px',
                      background: 'rgba(34, 197, 94, 0.15)',
                      color: '#16a34a',
                      fontWeight: 600
                    }}>
                      {certificate.status}
                    </span>
                  </div>

                  <div style={{ display: 'grid', gridTemplateColumns: '1fr', gap: '0.625rem', fontSize: '0.85rem' }}>
                    <div>
                      <span style={{ color: 'var(--text-muted, #64748b)', display: 'block', fontSize: '0.75rem' }}>주체 (Subject DN)</span>
                      <code style={{ fontFamily: 'monospace' }}>{certificate.subject}</code>
                    </div>
                    <div>
                      <span style={{ color: 'var(--text-muted, #64748b)', display: 'block', fontSize: '0.75rem' }}>발급자 (Issuer DN)</span>
                      <code style={{ fontFamily: 'monospace' }}>{certificate.issuer}</code>
                    </div>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.5rem' }}>
                      <div>
                        <span style={{ color: 'var(--text-muted, #64748b)', display: 'block', fontSize: '0.75rem' }}>일련번호 (Serial)</span>
                        <code style={{ fontFamily: 'monospace' }}>{certificate.serialNumber}</code>
                      </div>
                      <div>
                        <span style={{ color: 'var(--text-muted, #64748b)', display: 'block', fontSize: '0.75rem' }}>키 이름 / 버전</span>
                        <span style={{ fontWeight: 600 }}>{certificate.keyName} (v{certificate.keyVersion})</span>
                      </div>
                    </div>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.5rem' }}>
                      <div>
                        <span style={{ color: 'var(--text-muted, #64748b)', display: 'block', fontSize: '0.75rem' }}>유효기간</span>
                        <span>{new Date(certificate.notBefore).toLocaleDateString()} ~ {new Date(certificate.notAfter).toLocaleDateString()}</span>
                      </div>
                      <div>
                        <span style={{ color: 'var(--text-muted, #64748b)', display: 'block', fontSize: '0.75rem' }}>서명 백엔드</span>
                        <span style={{
                          fontSize: '0.75rem',
                          fontWeight: 600,
                          padding: '2px 6px',
                          borderRadius: '4px',
                          background: 'rgba(59, 130, 246, 0.1)',
                          color: '#2563eb'
                        }}>
                          {certificate.backend} (ECDSA P-256)
                        </span>
                      </div>
                    </div>
                  </div>
                </div>
              )}

              {/* PHYSICIAN SIGN REQUESTS & INFORMED APPROVAL PORTAL */}
              <div data-testid="doctor-sign-requests-section" style={{
                border: '1px solid var(--border-color, #e2e8f0)',
                borderRadius: '8px',
                padding: '1.25rem',
                background: '#ffffff',
                marginBottom: '1.5rem'
              }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                  <h4 style={{ margin: 0, fontSize: '1rem', fontWeight: 600, color: 'var(--text-main, #0f172a)' }}>
                    판독문 서명 승인 대기 목록 (Sign Requests)
                  </h4>
                  <span style={{ fontSize: '0.8rem', color: 'var(--text-muted, #64748b)' }}>
                    대기: {signRequests.filter(r => r.status === 'PENDING_APPROVAL').length}건
                  </span>
                </div>

                {signingSuccessMsg && (
                  <div data-testid="doctor-sign-success" style={{
                    padding: '0.75rem 1rem',
                    background: 'rgba(34, 197, 94, 0.1)',
                    border: '1px solid #22c55e',
                    borderRadius: '6px',
                    color: '#15803d',
                    marginBottom: '1rem',
                    fontSize: '0.875rem'
                  }}>
                    {signingSuccessMsg}
                  </div>
                )}

                {signRequests.length === 0 ? (
                  <p style={{ fontSize: '0.875rem', color: 'var(--text-muted, #64748b)', textAlign: 'center', padding: '1.5rem' }}>
                    현재 의사님께 배정된 승인 대기 판독문이 없습니다.
                  </p>
                ) : (
                  <div>
                    <div style={{ display: 'flex', gap: '8px', marginBottom: '1rem', overflowX: 'auto', paddingBottom: '4px' }}>
                      {signRequests.map(req => (
                        <button
                          key={req.id}
                          type="button"
                          data-testid="doctor-sign-request-item"
                          onClick={() => { setSelectedSignRequest(req); setSignConfirmed(false); setSigningSuccessMsg(null); }}
                          style={{
                            padding: '6px 12px',
                            borderRadius: '6px',
                            fontSize: '0.8rem',
                            fontWeight: 500,
                            border: selectedSignRequest?.id === req.id ? '2px solid var(--primary-color, #0284c7)' : '1px solid var(--border-color, #cbd5e1)',
                            background: selectedSignRequest?.id === req.id ? 'rgba(2, 132, 199, 0.08)' : '#f8fafc',
                            cursor: 'pointer',
                            whiteSpace: 'nowrap'
                          }}
                        >
                          요청 #{req.id.slice(0, 8)} ({req.status})
                        </button>
                      ))}
                    </div>

                    {selectedSignRequest && (
                      <div data-testid="document-preview-card" style={{
                        padding: '1rem',
                        border: '1px solid var(--border-color, #cbd5e1)',
                        borderRadius: '6px',
                        background: '#f8fafc',
                        marginBottom: '1rem'
                      }}>
                        <h5 style={{ margin: '0 0 0.5rem 0', fontSize: '0.9rem', fontWeight: 600 }}>
                          판독문 상세 사전검토 (Document Preview & Nonce)
                        </h5>
                        <div style={{ display: 'grid', gridTemplateColumns: '1fr', gap: '0.5rem', fontSize: '0.8rem' }}>
                          <div>
                            <span style={{ color: 'var(--text-muted, #64748b)' }}>요청 ID: </span>
                            <code style={{ fontFamily: 'monospace' }}>{selectedSignRequest.id}</code>
                          </div>
                          <div>
                            <span style={{ color: 'var(--text-muted, #64748b)' }}>대상 문서 ID: </span>
                            <code style={{ fontFamily: 'monospace' }}>{selectedSignRequest.documentId}</code>
                          </div>
                          <div>
                            <span style={{ color: 'var(--text-muted, #64748b)' }}>문서 SHA-256 해시: </span>
                            <code data-testid="preview-document-sha256" style={{ fontFamily: 'monospace', wordBreak: 'break-all' }}>
                              {selectedSignRequest.documentSha256 || '(자동 계산 해시)'}
                            </code>
                          </div>
                          <div>
                            <span style={{ color: 'var(--text-muted, #64748b)' }}>서명 챌린지 Nonce: </span>
                            <code data-testid="preview-request-nonce" style={{ fontFamily: 'monospace' }}>
                              {selectedSignRequest.nonce || '(미발급/완료됨)'}
                            </code>
                          </div>
                          <div>
                            <span style={{ color: 'var(--text-muted, #64748b)' }}>현재 처리 상태: </span>
                            <strong style={{ color: selectedSignRequest.status === 'COMPLETED' ? '#16a34a' : '#d97706' }}>
                              {selectedSignRequest.status}
                            </strong>
                          </div>
                        </div>

                        {/* Truthful Document Content Fetch & Preview */}
                        <div style={{ marginTop: '0.75rem', borderTop: '1px solid var(--border-color, #cbd5e1)', paddingTop: '0.75rem' }}>
                          <div style={{ fontWeight: 600, fontSize: '0.82rem', marginBottom: '0.4rem', color: 'var(--navy-900)' }}>
                            📄 판독문 원문 검토 (Bearer Authorized Document Content Preview):
                          </div>
                          {docContentLoading ? (
                            <div style={{ padding: '0.5rem', fontSize: '0.8rem', color: 'var(--text-muted)' }}>
                              판독문 원문 데이터를 안전하게 불러오는 중입니다...
                            </div>
                          ) : (
                            <pre
                              data-testid="preview-document-content"
                              style={{
                                padding: '0.75rem',
                                backgroundColor: '#fff',
                                border: '1px solid #cbd5e1',
                                borderRadius: '4px',
                                fontSize: '0.8rem',
                                lineHeight: '1.4',
                                maxHeight: '160px',
                                overflowY: 'auto',
                                whiteSpace: 'pre-wrap',
                                fontFamily: 'monospace',
                                margin: 0,
                              }}
                            >
                              {docContent || '(판독문 원문 데이터 없음)'}
                            </pre>
                          )}
                        </div>

                        {selectedSignRequest.status === 'PENDING_APPROVAL' && (
                          <div style={{ marginTop: '1rem', borderTop: '1px solid var(--border-color, #cbd5e1)', paddingTop: '0.75rem' }}>
                            <label style={{ display: 'flex', alignItems: 'flex-start', gap: '0.5rem', fontSize: '0.85rem', cursor: 'pointer', marginBottom: '0.75rem' }}>
                              <input
                                type="checkbox"
                                data-testid="doctor-sign-confirm-checkbox"
                                checked={signConfirmed}
                                onChange={e => setSignConfirmed(e.target.checked)}
                                style={{ marginTop: '0.15rem' }}
                              />
                              <span>
                                <strong>의사 서명 직접 승인:</strong> 본인은 상기 판독문 정보 및 SHA-256 해시를 검토하였으며, 위탁된 본인의 전자서명 개인키(ECDSA P-256)로 최종 전자서명을 승인합니다.
                              </span>
                            </label>

                            <button
                              type="button"
                              data-testid="doctor-sign-approve-btn"
                              disabled={loading || !signConfirmed}
                              onClick={handleApproveSignRequest}
                              style={{
                                width: '100%',
                                padding: '0.625rem',
                                fontSize: '0.9rem',
                                fontWeight: 600,
                                background: !signConfirmed ? 'var(--text-muted, #94a3b8)' : '#16a34a',
                                color: '#fff',
                                border: 'none',
                                borderRadius: '6px',
                                cursor: (!signConfirmed || loading) ? 'not-allowed' : 'pointer'
                              }}
                            >
                              {loading ? '서명 승인 처리 중...' : '최종 전자서명 승인 (Bearer Authorized Approval)'}
                            </button>
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                )}
              </div>

              <div style={{ textAlign: 'center' }}>
                <a
                  href="#/"
                  style={{
                    display: 'inline-block',
                    padding: '0.75rem 1.5rem',
                    background: 'var(--primary-color, #0284c7)',
                    color: '#fff',
                    borderRadius: '6px',
                    textDecoration: 'none',
                    fontWeight: 600,
                    fontSize: '0.9rem'
                  }}
                >
                  메인 대시보드로 이동
                </a>
              </div>
            </div>
          )}

          {/* CONSENTED (Key Provisioning pending) View */}
          {doctor.status !== 'ACTIVE' && (
            <div>
              <div style={{
                padding: '1rem',
                background: 'rgba(59, 130, 246, 0.05)',
                border: '1px solid rgba(59, 130, 246, 0.2)',
                borderRadius: '6px',
                fontSize: '0.85rem',
                marginBottom: '1.25rem',
                lineHeight: 1.6
              }}>
                <p style={{ margin: '0 0 0.5rem 0', fontWeight: 600 }}>키 생성 및 인증서 발급 전 확인사항:</p>
                <ul style={{ margin: 0, paddingLeft: '1.25rem', color: 'var(--text-muted, #64748b)' }}>
                  <li>OpenBao Transit Engine에 전용 ECDSA P-256 키가 비대칭 개인키로 생성됩니다.</li>
                  <li>생성된 키는 소프트웨어 보안 영역에 격리 저장되며, 외부로 반출되지 않습니다.</li>
                  <li>본 시스템의 사설 CA(Private CA)가 의사 정보와 공개키를 검증하여 X.509 인증서를 발행합니다.</li>
                  <li><strong>공지:</strong> 시스템의 Transit Auto-unseal은 HSM이 아니며, 키는 HSM-backed가 아닌 소프트웨어 관리형입니다.</li>
                </ul>
              </div>

              {activationError && (
                <div data-testid="activation-error" style={{
                  padding: '1rem',
                  background: 'rgba(239, 68, 68, 0.1)',
                  border: '1px solid #ef4444',
                  borderRadius: '6px',
                  color: '#b91c1c',
                  marginBottom: '1.25rem',
                  fontSize: '0.875rem'
                }}>
                  <div style={{ fontWeight: 600, marginBottom: '0.25rem' }}>키 발급 처리 중 오류 발생</div>
                  <div>{activationError}</div>
                  <div style={{ marginTop: '0.5rem', fontSize: '0.8rem', color: 'var(--text-muted, #64748b)' }}>
                    계정 상태는 <strong>CONSENTED</strong>로 안전하게 유지되며, 자동 활성화되지 않았습니다. 잠시 후 재시도하십시오.
                  </div>
                </div>
              )}

              <button
                type="button"
                data-testid="activate-btn"
                onClick={handleActivate}
                disabled={loading}
                style={{
                  width: '100%',
                  padding: '0.75rem',
                  fontSize: '0.95rem',
                  fontWeight: 600,
                  background: 'var(--primary-color, #0284c7)',
                  color: '#fff',
                  border: 'none',
                  borderRadius: '6px',
                  cursor: loading ? 'not-allowed' : 'pointer'
                }}
              >
                {loading ? '전자서명 키 및 인증서 발급 중...' : '최종 활성화: 전자서명 키 생성 및 인증서 발급'}
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
};
