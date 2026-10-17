import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useApp } from '../context/AppContext';
import { ApiClientError } from '../api/client';
import {
  CertificateEnrollmentConfig,
  CertificateChallenge,
  Enrollment,
  Doctor,
  DoctorCertificate,
  CertificateChallengePurpose,
} from '../types/api';
import {
  assertNoPrivateKeyOrPassword,
  parseCertificateChainPem,
  computeCertificateFingerprint,
} from '../utils/cryptoCert';
import { PhysicianSigningPortal } from './PhysicianSigningPortal';
import type { LocalSignResult } from '../services/localCertSdkAdapter';
import { CertificateSecurityStep } from './CertificateSecurityStep';
import { MedicalCredentialsForm } from './MedicalCredentialsForm';

export const CertificateEnrollmentView: React.FC<{
  initialMode?: 'PC_ENROLL' | 'PC_LOGIN';
  initialChallenge?: CertificateChallenge;
  onEnrollmentComplete?: () => void;
}> = ({ initialMode = 'PC_LOGIN', initialChallenge, onEnrollmentComplete }) => {
  const { client } = useApp();

  // Mode: PC_ENROLL (initial onboarding), PC_LOGIN (returning doctor), MOBILE, GOOGLE_AUX
  const [authMode, setAuthMode] = useState<'PC_ENROLL' | 'PC_LOGIN' | 'MOBILE' | 'GOOGLE'>(initialChallenge ? 'PC_ENROLL' : initialMode);

  // Step 1: Challenge Request
  // Step 2: Proof Submission & CA Validation
  // Step 3: Physician Consent
  // Step 4: Admin Review Observation, Key Activation & Signing Portal
  const [step, setStep] = useState<1 | 2 | 3 | 4>(initialChallenge ? 2 : 1);

  // Configuration from GET /v1/certificate-enrollment/config
  const [config, setConfig] = useState<CertificateEnrollmentConfig | null>(null);
  const [configLoading, setConfigLoading] = useState(false);
  const [configError, setConfigError] = useState<string | null>(null);

  // Invitation code and challenge
  const [challenge, setChallenge] = useState<CertificateChallenge | null>(initialChallenge ?? null);

  // Security critical: activationToken and active session must ONLY be kept in volatile component memory
  // NEVER write to localStorage, sessionStorage, or URL query parameters
  const [activationToken, setActivationToken] = useState<string | null>(null);
  const [doctor, setDoctor] = useState<Doctor | null>(null);
  const [enrollment, setEnrollment] = useState<Enrollment | null>(null);

  // Manual proof input state
  const [selectedAlgorithm, setSelectedAlgorithm] = useState<'SHA256withRSA' | 'SHA256withECDSA'>('SHA256withRSA');
  const [publicCertPem, setPublicCertPem] = useState('');
  const [chainPem, setChainPem] = useState('');
  const [signatureBase64, setSignatureBase64] = useState('');

  // Step 3: Consents (MUST default to unchecked)
  const [consentAuth, setConsentAuth] = useState(false);
  const [consentSig, setConsentSig] = useState(false);
  const [consentPriv, setConsentPriv] = useState(false);

  // Step 4: Final Key Activation / Certificate state
  const [certificate, setCertificate] = useState<DoctorCertificate | null>(null);
  const [refreshingEnrollment, setRefreshingEnrollment] = useState(false);

  // General loading & feedback
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [statusMessage, setStatusMessage] = useState<string | null>(null);

  // Mobile state
  const [mobileError, setMobileError] = useState<string | null>(null);

  const diagnostics = import.meta.env.DEV && import.meta.env.VITE_CERTIFICATE_DIAGNOSTICS === 'true';
  const operationVersion = useRef(0);
  useEffect(() => () => { operationVersion.current++; }, []);
  useEffect(() => {
    if (initialChallenge && certificate && doctor?.status === 'ACTIVE') onEnrollmentComplete?.();
  }, [initialChallenge, certificate, doctor?.status, onEnrollmentComplete]);

  // Load server configuration
  const loadConfig = useCallback(async () => {
    setConfigLoading(true);
    setConfigError(null);
    try {
      const cfg = await client.getCertificateEnrollmentConfig();
      setConfig(cfg);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : '인증서 설정 정보를 불러올 수 없습니다.';
      setConfigError(msg);
      // Strict defect #4: NEVER invent fallback devSimulationEnabled on error!
    } finally {
      setConfigLoading(false);
    }
  }, [client]);

  useEffect(() => {
    loadConfig();
  }, [loadConfig]);

  // Reset state when authMode changes (Security invariant: clear memory)
  const handleAuthModeChange = (mode: 'PC_ENROLL' | 'PC_LOGIN' | 'MOBILE' | 'GOOGLE') => {
    operationVersion.current++;
    setLoading(false);
    setConsentAuth(false); setConsentSig(false); setConsentPriv(false);
    setAuthMode(mode);
    setStep(1);
    setChallenge(null);
    setActivationToken(null);
    setDoctor(null);
    setEnrollment(null);
    setPublicCertPem('');
    setChainPem('');
    setSignatureBase64('');
    setError(null);
    setStatusMessage(null);
    setMobileError(null);
  };

  // Sign out / clear session
  const handleSignOut = () => {
    operationVersion.current++;
    setActivationToken(null);
    setDoctor(null);
    setEnrollment(null);
    setCertificate(null);
    setStep(1);
    setChallenge(null);
    setStatusMessage('서명 세션이 안전하게 종료되었습니다.');
  };

  // Step 1: Request Challenge
  const handleRequestChallenge = async (purpose: CertificateChallengePurpose) => {
    setLoading(true);
    setError(null);
    setStatusMessage(null);
    try {
      let chal: CertificateChallenge;
      if (purpose === 'CERTIFICATE_LOGIN') {
        chal = await client.createCertificateLoginChallenge();
      } else {
        throw new Error('최초 인증서 등록은 PACS에서 본인 계정을 확인한 뒤 시작해주세요.');
      }
      setChallenge(chal);
      setStep(2);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : '전자서명 챌린지 생성에 실패했습니다.';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  // Step 2: Download challenge signedPayload bytes as binary file
  const handleDownloadChallengeBytes = () => {
    if (!challenge) return;
    const blob = new Blob([challenge.signedPayload], { type: 'application/octet-stream' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `challenge-${challenge.challengeId.slice(0, 8)}.bin`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  // Step 2: Submit Proof
  const handleSubmitProof = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!challenge) return;

    setError(null);
    setStatusMessage(null);

    // 1. Strict zero private key assertion
    try {
      assertNoPrivateKeyOrPassword(publicCertPem);
      if (chainPem) {
        assertNoPrivateKeyOrPassword(chainPem);
      }
      assertNoPrivateKeyOrPassword(signatureBase64);
    } catch (validationErr: unknown) {
      const msg = validationErr instanceof Error ? validationErr.message : '보안 검증 실패';
      setError(msg);
      return;
    }

    // 2. Parse PEM into clean Base64 DER
    let leafDerBase64: string;
    let chainDerArray: string[] = [];
    try {
      const parsedLeaf = parseCertificateChainPem(publicCertPem);
      leafDerBase64 = parsedLeaf.leaf;
      if (parsedLeaf.chain.length > 0) {
        chainDerArray.push(...parsedLeaf.chain);
      }
      if (chainPem.trim()) {
        const parsedChain = parseCertificateChainPem(chainPem);
        chainDerArray.push(parsedChain.leaf, ...parsedChain.chain);
      }
    } catch (parseErr: unknown) {
      const msg = parseErr instanceof Error ? parseErr.message : '인증서 PEM 파싱 실패';
      setError(msg);
      return;
    }

    await submitSdkProof({ certificateDerBase64: leafDerBase64, chainDerBase64: chainDerArray,
      signatureBase64: signatureBase64.trim(), signatureAlgorithm: selectedAlgorithm, fingerprintSha256: '' }).catch(() => undefined);
  };

  const submitSdkProof = async (proof: LocalSignResult) => {
    if (!challenge || !config || !config.pcCertificate.trustConfigured) {
      setError('인증서 서비스를 확인할 수 없습니다. 잠시 후 다시 시도해주세요.');
      return;
    }
    if (!Number.isFinite(Date.parse(challenge.expiresAt)) || Date.parse(challenge.expiresAt) <= Date.now()) {
      setError('인증 시간이 만료되었습니다. 새 인증 요청으로 다시 진행해주세요.');
      return;
    }
    const version = operationVersion.current;
    setError(null);
    setStatusMessage(null);
    setLoading(true);
    try {
      if (authMode === 'PC_LOGIN') {
        // Compute full-DER SHA-256 fingerprint for login
        const fingerprintSha256 = await computeCertificateFingerprint(proof.certificateDerBase64);

        const res = await client.loginWithCertificate({
          challengeId: challenge.challengeId,
          fingerprintSha256,
          certificate: proof.certificateDerBase64,
          chain: proof.chainDerBase64,
          signature: proof.signatureBase64,
          signatureAlgorithm: proof.signatureAlgorithm,
        });

        if (version !== operationVersion.current) return;
        setActivationToken(res.activationToken);
        setEnrollment(res.enrollment);
        setDoctor(res.doctor);
        if (res.doctor.certificate) {
          setCertificate(res.doctor.certificate);
        }
        setStep(4);
        setStatusMessage('기존 등록 인증서로 성공적으로 로그인되었습니다.');
      } else {
        const res = await client.verifyCertificateProof({
          registrationId: challenge.registrationId || challenge.challengeId,
          certificate: proof.certificateDerBase64,
          chain: proof.chainDerBase64,
          signature: proof.signatureBase64,
          signatureAlgorithm: proof.signatureAlgorithm,
        });

        if (version !== operationVersion.current) return;
        setEnrollment(res.enrollment);
        setDoctor(res.doctor);
        if (res.activationToken) {
          setActivationToken(res.activationToken);
        }

        // Fail-Closed Check on CA Revocation status
        if (res.enrollment.externalStatus === 'REVOKED') {
          setError('해당 외부 인증서는 폐기(REVOKED) 상태로 확인되어 등록할 수 없습니다.');
          return;
        }

        if (res.enrollment.externalStatus === 'UNKNOWN' || res.enrollment.externalStatus === 'STALE') {
          setError(`외부 CA 폐기 검증 상태가 정상이지 않습니다 (${res.enrollment.externalStatus}). 등록이 차단됩니다.`);
          return;
        }

        // Each enrollment/renewal needs fresh explicit consent.
        setConsentAuth(false); setConsentSig(false); setConsentPriv(false);
        setStep(3);
      }
    } catch (err: unknown) {
      if (version !== operationVersion.current) return;
      const msg = err instanceof Error ? err.message : '인증서 확인에 실패했습니다.';
      setError(msg);
      throw err;
    } finally {
      if (version === operationVersion.current) setLoading(false);
    }
  };

  // Step 2 Dev Shortcut: Mock Certificate Sign (opt-in server flag only)
  const handleDevMockSign = async () => {
    if (!challenge) return;
    setLoading(true);
    setError(null);
    try {
      const res = await client.devMockSign(challenge.challengeId);
      setEnrollment(res.enrollment);
      setDoctor(res.doctor);
      setActivationToken(res.activationToken);

      if (authMode === 'PC_LOGIN') {
        if (res.doctor.certificate) {
          setCertificate(res.doctor.certificate);
        }
        setStep(4);
        setStatusMessage('[개발전용] Mock Certificate 세션으로 로그인되었습니다.');
      } else {
        setStep(3);
      }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Dev Mock Certificate 서명 처리에 실패했습니다.';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  // Step 3: Consent submission
  const handleConsentSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!activationToken || !enrollment) {
      setError('활성화 세션 토큰이 유실되었습니다. 처음부터 다시 진행해주세요.');
      return;
    }
    if (enrollment.medicalCredentialsRequired && !enrollment.medicalCredentialsSubmittedAt) {
      setError('의사면허 및 전문의 자격 정보를 먼저 제출해주세요.');
      return;
    }
    if (!consentAuth || !consentSig || !consentPriv) {
      setError('모든 필수 동의 항목에 동의해야 합니다.');
      return;
    }

    setLoading(true);
    setError(null);
    try {
      // 1. Submit consent to enrollment endpoint (advances doctor state and records consent)
      const updatedEnrollment = await client.submitCertificateConsent(activationToken, enrollment.registrationId, {
        version: '2026-10-13-draft',
        authentication: true,
        signature: true,
        privacy: true,
      });
      setEnrollment(updatedEnrollment);

      // 2. Fetch updated doctor state from /doctor-activation/me
      try {
        const me = await client.getDoctorMe(activationToken);
        if (me.doctor) {
          setDoctor(me.doctor);
          if (me.doctor.certificate) {
            setCertificate(me.doctor.certificate);
          }
        }
      } catch (meErr) {
        console.warn('Failed to fetch doctor profile after consent:', meErr);
      }

      setStep(4);
      setStatusMessage('의사 전자서명 위탁 동의가 완료되었습니다.');
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : '동의 정보 제출에 실패했습니다.';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  // Step 4: Refresh enrollment to check admin review status
  const handleRefreshEnrollment = async () => {
    if (!activationToken) return;
    setRefreshingEnrollment(true);
    setError(null);
    try {
      const list = await client.getCertificateEnrollments(activationToken);
      if (list.items && list.items.length > 0) {
        setEnrollment(list.items[0]);
      }
      const me = await client.getDoctorMe(activationToken);
      if (me.doctor) {
        setDoctor(me.doctor);
        if (me.doctor.certificate) {
          setCertificate(me.doctor.certificate);
        }
      }
    } catch (err: unknown) {
      console.error('Failed to refresh enrollment', err);
      const isAuthError = err instanceof ApiClientError && (err.status === 401 || err.status === 403);
      const msg = isAuthError
        ? '인증 세션이 만료되었습니다. 새 인증서로 다시 로그인하십시오.'
        : (err instanceof Error ? err.message : '등록 상태 갱신에 실패했습니다.');
      setError(msg);
    } finally {
      setRefreshingEnrollment(false);
    }
  };

  // Step 4: Final key activation (OpenBao Transit key provisioning)
  const handleActivate = async () => {
    if (!activationToken) return;
    setLoading(true);
    setError(null);
    try {
      const activatedDoctor = await client.activateDoctor(activationToken);
      setDoctor(activatedDoctor);
      setCertificate(activatedDoctor.certificate);
      setStatusMessage('의사 전자서명 키 생성 및 인증서 발급이 성공적으로 완료되었습니다.');
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : '전자서명 키 발급 처리에 실패했습니다.';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  // Step 4: Unregister certificate
  const handleUnregister = async () => {
    if (!activationToken || !enrollment) return;
    const ok = window.confirm(
      '정말로 등록된 인증서를 해지(Unregister)하시겠습니까?\n해지 시 모든 전자서명 세션이 무효화되며 서명 승인이 차단됩니다.'
    );
    if (!ok) return;

    setLoading(true);
    setError(null);
    try {
      await client.unregisterDoctorCertificate(activationToken, enrollment.registrationId, {
        reason: 'Physician requested unregistration',
      });
      alert('인증서 등록이 해지되었습니다. 세션이 종료됩니다.');
      handleSignOut();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : '인증서 등록 해지 처리에 실패했습니다.';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  // Step 4: Start renewal
  const handleStartRenewal = async () => {
    if (!activationToken) return;
    setLoading(true);
    setError(null);
    try {
      const chal = await client.renewDoctorCertificate(activationToken);
      setChallenge(chal);
      setStep(2);
      setStatusMessage('갱신용 전자서명 챌린지가 발급되었습니다. 새 인증서로 서명을 제출하십시오.');
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : '인증서 갱신 챌린지 생성 실패';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="container" style={{ maxWidth: '800px', margin: '2rem auto', padding: '0 1rem' }}>
      {/* Header Banner: Two-Tier Architecture Explanation */}
      <div
        className="card"
        style={{
          borderLeft: '4px solid var(--primary-color, #0284c7)',
          background: 'var(--bg-subtle, #f8fafc)',
          padding: '1.25rem',
          marginBottom: '1.5rem',
        }}
        data-testid="cert-architecture-banner"
      >
        <h2 style={{ margin: '0 0 0.5rem 0', fontSize: '1.25rem', color: 'var(--primary-color, #0284c7)' }}>
          {authMode === 'PC_LOGIN' ? '의료인 공동인증서 로그인' : '의료인 공동인증서 등록'}
        </h2>
        <p style={{ margin: 0, fontSize: '0.875rem', lineHeight: '1.5', color: 'var(--text-muted, #64748b)' }}>
          공동인증서로 본인임을 확인하고 의사면허 및 전문의 자격 정보를 등록합니다. 등록 이후에는 공동인증서로 로그인해주세요.
        </p>
      </div>

      {/* Config loading error banner with retry */}
      {configError && (
        <div
          data-testid="cert-config-error"
          style={{
            padding: '1rem',
            background: 'rgba(239, 68, 68, 0.1)',
            border: '1px solid #ef4444',
            borderRadius: '6px',
            color: '#b91c1c',
            marginBottom: '1.5rem',
          }}
        >
          <div style={{ fontWeight: 600, marginBottom: '0.25rem' }}>인증서 서비스 설정 로드 실패</div>
          <div style={{ fontSize: '0.85rem', marginBottom: '0.5rem' }}>{configError}</div>
          <button
            type="button"
            data-testid="cert-config-retry-btn"
            onClick={loadConfig}
            disabled={configLoading}
            style={{
              padding: '4px 12px',
              fontSize: '0.8rem',
              fontWeight: 600,
              background: '#ef4444',
              color: '#fff',
              border: 'none',
              borderRadius: '4px',
              cursor: 'pointer',
            }}
          >
            {configLoading ? '재조회 중...' : '다시 시도'}
          </button>
        </div>
      )}

      {/* Navigation Tabs */}
      {!initialChallenge && <div style={{ display: 'flex', gap: '8px', borderBottom: '1px solid var(--border-color, #e2e8f0)', marginBottom: '1.5rem' }}>
        <button
          type="button"
          data-testid="tab-cert-enroll"
          onClick={() => handleAuthModeChange('PC_ENROLL')}
          style={{
            padding: '0.75rem 1rem',
            background: 'none',
            border: 'none',
            borderBottom: authMode === 'PC_ENROLL' ? '2px solid var(--primary-color, #0284c7)' : '2px solid transparent',
            color: authMode === 'PC_ENROLL' ? 'var(--primary-color, #0284c7)' : 'var(--text-muted, #64748b)',
            fontWeight: authMode === 'PC_ENROLL' ? 600 : 400,
            cursor: 'pointer',
          }}
        >
          공동인증서 직접 등록
        </button>
        <button
          type="button"
          data-testid="tab-cert-login"
          onClick={() => handleAuthModeChange('PC_LOGIN')}
          style={{
            padding: '0.75rem 1rem',
            background: 'none',
            border: 'none',
            borderBottom: authMode === 'PC_LOGIN' ? '2px solid var(--primary-color, #0284c7)' : '2px solid transparent',
            color: authMode === 'PC_LOGIN' ? 'var(--primary-color, #0284c7)' : 'var(--text-muted, #64748b)',
            fontWeight: authMode === 'PC_LOGIN' ? 600 : 400,
            cursor: 'pointer',
          }}
        >
          등록 인증서 로그인
        </button>
        <button
          type="button"
          data-testid="tab-mobile-cert"
          onClick={() => handleAuthModeChange('MOBILE')}
          style={{
            padding: '0.75rem 1rem',
            background: 'none',
            border: 'none',
            borderBottom: authMode === 'MOBILE' ? '2px solid var(--primary-color, #0284c7)' : '2px solid transparent',
            color: authMode === 'MOBILE' ? 'var(--primary-color, #0284c7)' : 'var(--text-muted, #64748b)',
            fontWeight: authMode === 'MOBILE' ? 600 : 400,
            cursor: 'pointer',
          }}
        >
          모바일 간편인증
        </button>
        <button
          type="button"
          data-testid="tab-google-aux"
          onClick={() => handleAuthModeChange('GOOGLE')}
          style={{
            padding: '0.75rem 1rem',
            background: 'none',
            border: 'none',
            borderBottom: authMode === 'GOOGLE' ? '2px solid var(--primary-color, #0284c7)' : '2px solid transparent',
            color: authMode === 'GOOGLE' ? 'var(--primary-color, #0284c7)' : 'var(--text-muted, #64748b)',
            fontWeight: authMode === 'GOOGLE' ? 600 : 400,
            cursor: 'pointer',
          }}
        >
          Google 보조 신원확인
        </button>
      </div>}

      {/* Status & Error Messages */}
      {error && (
        <div
          data-testid="cert-enrollment-error"
          style={{
            padding: '1rem',
            background: 'rgba(239, 68, 68, 0.1)',
            border: '1px solid #ef4444',
            borderRadius: '6px',
            color: '#b91c1c',
            marginBottom: '1.25rem',
            fontSize: '0.875rem',
          }}
        >
          {error}
        </div>
      )}

      {statusMessage && (
        <div
          data-testid="cert-enrollment-status"
          style={{
            padding: '1rem',
            background: 'rgba(34, 197, 94, 0.1)',
            border: '1px solid #22c55e',
            borderRadius: '6px',
            color: '#15803d',
            marginBottom: '1.25rem',
            fontSize: '0.875rem',
          }}
        >
          {statusMessage}
        </div>
      )}

      {(authMode === 'PC_ENROLL' || authMode === 'PC_LOGIN') && <ol className="medical-registration-progress" aria-label="인증 진행 순서">
        {(authMode === 'PC_LOGIN' ? ['인증서 확인', '로그인 완료'] : ['인증서 확인', '면허·자격 및 동의', '등록 결과']).map((label, index) => {
          const active = step <= 2 ? 0 : authMode === 'PC_LOGIN' ? 1 : step === 3 ? 1 : 2;
          return <li key={label} aria-current={index === active ? 'step' : undefined}>{String(index + 1).padStart(2, '0')} {label}</li>;
        })}
      </ol>}

      {/* STEP 1: Challenge Initiation */}
      {step === 1 && (
        <div className="card" style={{ padding: '1.5rem', background: 'var(--bg-card, #ffffff)', borderRadius: '8px', border: '1px solid var(--border-color, #e2e8f0)' }}>
          {authMode === 'PC_ENROLL' && (
            <div data-testid="pc-enroll-step-1">
              <h3>판독의사 공동인증서 직접 등록</h3>
              <p>PACS 로그인 화면에서 본인 계정을 확인한 뒤 공동인증서를 등록해주세요. 관리자 초대코드는 필요하지 않습니다.</p>
              <p>등록 후에도 매번 공동인증서로 로그인합니다.</p>
              <a className="btn btn-primary" href="#/certificates/pacs-enroll">최초 등록 안내</a>
            </div>
          )}

          {authMode === 'PC_LOGIN' && (
            <div data-testid="pc-login-step-1">
              <h3 style={{ fontSize: '1.1rem', fontWeight: 600, marginBottom: '0.5rem' }}>
                등록한 공동인증서로 로그인
              </h3>
              <p style={{ color: 'var(--text-muted, #64748b)', fontSize: '0.85rem', marginBottom: '1.25rem' }}>
                이미 등록이 승인된 외부 인증서를 사용하여 로그인 챌린지 서명을 제출합니다.
              </p>

              <button
                type="button"
                data-testid="request-login-challenge-btn"
                disabled={loading}
                onClick={() => handleRequestChallenge('CERTIFICATE_LOGIN')}
                style={{
                  width: '100%',
                  padding: '0.75rem',
                  fontSize: '0.95rem',
                  fontWeight: 600,
                  background: 'var(--primary-color, #0284c7)',
                  color: '#fff',
                  border: 'none',
                  borderRadius: '6px',
                  cursor: loading ? 'not-allowed' : 'pointer',
                }}
              >
                {loading ? '로그인 준비 중…' : '공동인증서 로그인 시작'}
              </button>
            </div>
          )}

          {authMode === 'MOBILE' && (
            <div data-testid="mobile-cert-step-1">
              <h3 style={{ fontSize: '1.1rem', fontWeight: 600, marginBottom: '0.5rem' }}>
                모바일 간편인증 (통신사/간편인증사)
              </h3>

              {/* Strict Defect #6 & #8: Server configured provider only, honest 503 notice */}
              <div
                data-testid="mobile-unconfigured-banner"
                style={{
                  padding: '1rem',
                  background: 'rgba(234, 179, 8, 0.1)',
                  border: '1px solid rgba(234, 179, 8, 0.4)',
                  borderRadius: '6px',
                  fontSize: '0.85rem',
                  color: 'var(--text-main, #0f172a)',
                  marginBottom: '1rem',
                  lineHeight: '1.5',
                }}
              >
                <strong>⚠️ 모바일 인증 공급자 안내:</strong><br />
                서버 설정 공급자: <code>{config?.mobile?.provider || '없음'}</code><br />
                통신사/간편인증사 모바일 인증 어댑터가 서버에 설정되어 있지 않습니다 (503 MOBILE_PROVIDER_NOT_CONFIGURED).
                현재 개발 환경에서는 가짜 시뮬레이션을 생성하지 않으며, PC 공동/금융인증서 탭을 이용하셔야 합니다.
              </div>

              <button
                type="button"
                data-testid="start-mobile-transaction-btn"
                disabled={!config?.mobile?.configured || loading}
                onClick={async () => {
                  if (!config?.mobile?.configured) {
                    setMobileError('통신사/간편인증사 연동 모듈 미설정 (503 MOBILE_PROVIDER_NOT_CONFIGURED). PC 인증서 탭을 사용하십시오.');
                    return;
                  }
                  try {
                    setLoading(true);
                    setMobileError(null);
                    await client.createMobileCertificateTransaction(
                      'CERTIFICATE_ENROLLMENT',
                      undefined
                    );
                  } catch (err: unknown) {
                    setMobileError(err instanceof Error ? err.message : '모바일 인증 요청 실패');
                  } finally {
                    setLoading(false);
                  }
                }}
                style={{
                  width: '100%',
                  padding: '0.75rem',
                  fontSize: '0.95rem',
                  fontWeight: 600,
                  background: !config?.mobile?.configured ? '#cbd5e1' : 'var(--primary-color, #0284c7)',
                  color: '#fff',
                  border: 'none',
                  borderRadius: '6px',
                  cursor: !config?.mobile?.configured ? 'not-allowed' : 'pointer',
                }}
              >
                {!config?.mobile?.configured ? '모바일 간편인증 비활성화 (미설정)' : '모바일 간편인증 요청 시작'}
              </button>

              {mobileError && (
                <div style={{ marginTop: '0.75rem', color: '#b91c1c', fontSize: '0.8rem' }}>
                  {mobileError}
                </div>
              )}
            </div>
          )}

          {authMode === 'GOOGLE' && (
            <div data-testid="google-aux-step-1">
              <h3 style={{ fontSize: '1.1rem', fontWeight: 600, marginBottom: '0.5rem' }}>
                Google 계정 연동 (보조 신원확인)
              </h3>
              <div
                style={{
                  padding: '1rem',
                  background: 'rgba(234, 179, 8, 0.1)',
                  border: '1px solid rgba(234, 179, 8, 0.4)',
                  borderRadius: '6px',
                  fontSize: '0.85rem',
                  color: 'var(--text-main, #0f172a)',
                  marginBottom: '1.25rem',
                  lineHeight: '1.5',
                }}
              >
                <strong>서버 의무 정책 안내 (CERTIFICATE_REQUIRED):</strong><br />
                본 시스템의 기본 활성화 정책은 <code>CERTIFICATE_REQUIRED</code>입니다.
                Google 계정 연동은 보조 신원확인 수단일 뿐이며, 단독으로 전자서명 키를 활성화할 수 없습니다.
                의사 전자서명 키 발급을 위해서는 외부 공동/금융인증서 등록이 필수적입니다.
              </div>
              <button
                type="button"
                onClick={() => handleAuthModeChange('PC_ENROLL')}
                style={{
                  padding: '0.625rem 1.25rem',
                  background: 'var(--primary-color, #0284c7)',
                  color: '#fff',
                  border: 'none',
                  borderRadius: '6px',
                  cursor: 'pointer',
                  fontSize: '0.9rem',
                  fontWeight: 600,
                }}
              >
                PC 공동/금융인증서 등록으로 이동
              </button>
            </div>
          )}
        </div>
      )}

      {/* STEP 2: Challenge Display, Local Bridge Guidance & Manual Signature Import */}
      {step === 2 && challenge && (
        <div className="card" style={{ padding: '1.5rem', background: 'var(--bg-card, #ffffff)', borderRadius: '8px', border: '1px solid var(--border-color, #e2e8f0)' }} data-testid="cert-step-2">
          <h3 style={{ fontSize: '1.1rem', fontWeight: 600, marginBottom: '0.5rem' }}>
            공동인증서 확인
          </h3>

          {/* CA Revocation / Fail-Closed Status Alert */}
          {enrollment && enrollment.externalStatus !== 'GOOD' && (
            <div
              data-testid="ca-status-alert"
              style={{
                padding: '1rem',
                background: 'rgba(239, 68, 68, 0.1)',
                border: '1px solid #ef4444',
                borderRadius: '6px',
                color: '#991b1b',
                marginBottom: '1.25rem',
              }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
                <strong>⚠️ 외부 CA 상태 경고: {enrollment.externalStatus}</strong>
                <span
                  style={{
                    padding: '2px 8px',
                    borderRadius: '12px',
                    fontSize: '0.75rem',
                    fontWeight: 700,
                    background: '#ef4444',
                    color: '#fff',
                  }}
                >
                  {enrollment.externalStatus}
                </span>
              </div>
              <p style={{ margin: '0 0 0.5rem 0', fontSize: '0.85rem' }}>
                {error || '외부 CA 폐기 상태 확인으로 인해 서명 검증이 차단되었습니다.'}
              </p>
              <div
                data-testid="ca-block-activation-notice"
                style={{
                  padding: '0.5rem',
                  background: '#fee2e2',
                  borderRadius: '4px',
                  fontSize: '0.8rem',
                  fontWeight: 600,
                  color: '#7f1d1d',
                }}
              >
                외부 CA 유효성 검증 실패({enrollment.externalStatus})로 인해 의사 서명키 등록 및 위탁 활성화가 차단(Fail-Closed)되었습니다.
              </div>
            </div>
          )}

          <CertificateSecurityStep key={challenge.challengeId} challenge={challenge} busy={loading || configLoading || !config || !config.pcCertificate.trustConfigured}
            onProof={submitSdkProof} onRestart={initialChallenge ? undefined : () => { if (authMode === 'PC_LOGIN') void handleRequestChallenge('CERTIFICATE_LOGIN'); else void handleStartRenewal(); }} onExpired={() => setError(initialChallenge
              ? '인증 시간이 만료되었습니다. 이 창을 닫고 PACS에서 인증서 등록을 다시 시작해주세요.'
              : '인증 시간이 만료되었습니다. 처음으로 돌아가 새 인증 요청을 받아주세요.')} />
          {diagnostics && <details data-testid="certificate-diagnostics"><summary>개발·연동 진단</summary>
          {/* Challenge Box */}
          <div
            style={{
              padding: '1rem',
              background: 'var(--bg-subtle, #f8fafc)',
              border: '1px solid var(--border-color, #e2e8f0)',
              borderRadius: '6px',
              marginBottom: '1.25rem',
              fontSize: '0.85rem',
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
              <strong>서명 대상 챌린지 (Exact Signed Payload Bytes)</strong>
              <span style={{ fontSize: '0.75rem', color: 'var(--text-muted, #64748b)' }}>
                만료 시각: {new Date(challenge.expiresAt).toLocaleTimeString()}
              </span>
            </div>
            <div
              data-testid="canonical-bytes-preview"
              style={{
                background: '#ffffff',
                padding: '0.75rem',
                borderRadius: '4px',
                border: '1px solid var(--border-color, #cbd5e1)',
                fontFamily: 'monospace',
                fontSize: '0.8rem',
                wordBreak: 'break-all',
                whiteSpace: 'pre-wrap',
                marginBottom: '0.75rem',
              }}
            >
              {challenge.signedPayload}
            </div>

            <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
              <button
                type="button"
                data-testid="download-challenge-btn"
                onClick={handleDownloadChallengeBytes}
                style={{
                  padding: '4px 10px',
                  fontSize: '0.75rem',
                  borderRadius: '4px',
                  border: '1px solid var(--border-color, #cbd5e1)',
                  background: '#fff',
                  cursor: 'pointer',
                }}
              >
                바이너리 다운로드 (challenge.bin)
              </button>
            </div>
          </div>

          {/* Dev Mock Sign Shortcut (conspicuous, dev profile & server flag only) */}
          {config?.devMockCertificateEnabled && (
            <div
              data-testid="dev-mock-sign-box"
              style={{
                padding: '1rem',
                border: '1px dashed #d97706',
                borderRadius: '6px',
                background: 'rgba(217, 119, 6, 0.06)',
                marginBottom: '1.5rem',
              }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <div>
                  <span style={{ fontSize: '0.75rem', fontWeight: 700, color: '#b45309', background: '#fef3c7', padding: '2px 6px', borderRadius: '4px' }}>
                    개발/QA 전용 시뮬레이션
                  </span>
                  <h4 style={{ margin: '4px 0 2px 0', fontSize: '0.9rem', color: '#92400e' }}>
                    서버 합성 Mock Certificate 서명 (MOCK_CERTIFICATE)
                  </h4>
                  <p style={{ margin: 0, fontSize: '0.78rem', color: '#b45309' }}>
                    실제 인증서 없이 개발 검증을 진행합니다. 신원 모드는 <code>MOCK_CERTIFICATE</code>로 명시되며 관리자 심사 및 동의 절차가 동일하게 적용됩니다.
                  </p>
                </div>
                <button
                  type="button"
                  data-testid="dev-mock-sign-btn"
                  disabled={loading}
                  onClick={handleDevMockSign}
                  style={{
                    padding: '6px 12px',
                    fontSize: '0.8rem',
                    fontWeight: 600,
                    background: '#d97706',
                    color: '#fff',
                    border: 'none',
                    borderRadius: '4px',
                    cursor: loading ? 'not-allowed' : 'pointer',
                    whiteSpace: 'nowrap',
                  }}
                >
                  Mock 서명 제출
                </button>
              </div>
            </div>
          )}

          {/* Manual Signature Import for Integration Labs */}
          <div
            data-testid="manual-signature-import-box"
            style={{
              padding: '1.25rem',
              border: '2px dashed var(--teal-600, #0d9488)',
              borderRadius: '8px',
              background: 'rgba(13, 148, 136, 0.04)',
              marginBottom: '1.5rem',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '0.75rem' }}>
              <span
                style={{
                  background: 'var(--teal-600, #0d9488)',
                  color: '#fff',
                  fontSize: '0.7rem',
                  fontWeight: 700,
                  padding: '2px 6px',
                  borderRadius: '4px',
                }}
              >
                검증 및 연동 랩
              </span>
              <h4 style={{ margin: 0, fontSize: '0.95rem', fontWeight: 600, color: 'var(--teal-900, #134e4a)' }}>
                외부 서명 응답 수동 가져오기 (Manual Signature Response Import)
              </h4>
            </div>

            {/* MANDATORY SECURITY WARNING: NO PRIVATE KEYS */}
            <div
              style={{
                padding: '0.75rem',
                background: 'rgba(239, 68, 68, 0.1)',
                border: '1px solid #ef4444',
                borderRadius: '6px',
                fontSize: '0.8rem',
                color: '#991b1b',
                marginBottom: '1rem',
                lineHeight: 1.5,
              }}
            >
              <strong>⚠️ 보안 준수 경고 (개인키 입력 절대 금지):</strong><br />
              본 시스템은 <strong>공개 인증서(Public Certificate) 및 서명 결과값(Signature Bytes)만 수신</strong>합니다.
              인증서 개인키(.p12, .pfx, .key) 또는 인증서 비밀번호는 <strong>절대 입력하거나 업로드하지 마십시오</strong>.
              외부 인증 도구에서 생성된 공개 서명 결과값만 안전하게 붙여넣으십시오.
            </div>

            <form onSubmit={handleSubmitProof}>
              <div style={{ marginBottom: '1rem' }}>
                <label style={{ display: 'block', fontSize: '0.8rem', fontWeight: 600, marginBottom: '0.25rem' }}>
                  서명 알고리즘 (Algorithm)
                </label>
                <select
                  data-testid="cert-algorithm-select"
                  value={selectedAlgorithm}
                  onChange={e => setSelectedAlgorithm(e.target.value as any)}
                  style={{
                    width: '100%',
                    padding: '0.5rem',
                    fontSize: '0.85rem',
                    borderRadius: '4px',
                    border: '1px solid var(--border-color, #cbd5e1)',
                    background: '#fff',
                  }}
                >
                  <option value="SHA256withRSA">SHA256withRSA (2048-bit 이상 공동인증서)</option>
                  <option value="SHA256withECDSA">SHA256withECDSA (P-256 금융/보안인증서)</option>
                </select>
              </div>

              <div style={{ marginBottom: '1rem' }}>
                <label style={{ display: 'block', fontSize: '0.8rem', fontWeight: 600, marginBottom: '0.25rem' }}>
                  공개 인증서 데이터 (Leaf Certificate PEM 또는 Base64 DER) <span style={{ color: '#ef4444' }}>*</span>
                </label>
                <textarea
                  data-testid="public-cert-input"
                  required
                  rows={4}
                  value={publicCertPem}
                  onChange={e => setPublicCertPem(e.target.value)}
                  placeholder="-----BEGIN CERTIFICATE----- ... -----END CERTIFICATE----- 또는 Base64 DER"
                  style={{
                    width: '100%',
                    padding: '0.5rem',
                    fontSize: '0.8rem',
                    fontFamily: 'monospace',
                    borderRadius: '4px',
                    border: '1px solid var(--border-color, #cbd5e1)',
                    background: '#fff',
                  }}
                />
              </div>

              <div style={{ marginBottom: '1rem' }}>
                <label style={{ display: 'block', fontSize: '0.8rem', fontWeight: 600, marginBottom: '0.25rem' }}>
                  중계 CA 체인 인증서 (Chain / Intermediate CA PEM, 선택)
                </label>
                <textarea
                  data-testid="cert-chain-input"
                  rows={2}
                  value={chainPem}
                  onChange={e => setChainPem(e.target.value)}
                  placeholder="필요한 경우 중계 CA 인증서 PEM을 붙여넣으십시오 (선택)"
                  style={{
                    width: '100%',
                    padding: '0.5rem',
                    fontSize: '0.8rem',
                    fontFamily: 'monospace',
                    borderRadius: '4px',
                    border: '1px solid var(--border-color, #cbd5e1)',
                    background: '#fff',
                  }}
                />
              </div>

              <div style={{ marginBottom: '1.25rem' }}>
                <label style={{ display: 'block', fontSize: '0.8rem', fontWeight: 600, marginBottom: '0.25rem' }}>
                  챌린지 서명 결과값 (Signature DER Base64) <span style={{ color: '#ef4444' }}>*</span>
                </label>
                <textarea
                  data-testid="signature-bytes-input"
                  required
                  rows={3}
                  value={signatureBase64}
                  onChange={e => setSignatureBase64(e.target.value)}
                  placeholder="외부 서명 모듈에서 출력된 Base64 서명값"
                  style={{
                    width: '100%',
                    padding: '0.5rem',
                    fontSize: '0.8rem',
                    fontFamily: 'monospace',
                    borderRadius: '4px',
                    border: '1px solid var(--border-color, #cbd5e1)',
                    background: '#fff',
                  }}
                />
              </div>

              <button
                type="submit"
                data-testid="submit-cert-proof-btn"
                disabled={loading || !publicCertPem.trim() || !signatureBase64.trim()}
                style={{
                  width: '100%',
                  padding: '0.75rem',
                  fontSize: '0.95rem',
                  fontWeight: 600,
                  background: 'var(--teal-600, #0d9488)',
                  color: '#fff',
                  border: 'none',
                  borderRadius: '6px',
                  cursor: (loading || !publicCertPem.trim() || !signatureBase64.trim()) ? 'not-allowed' : 'pointer',
                }}
              >
                {loading ? '인증서 체인 및 CRL 폐기 검증 중...' : '인증서 체인/CRL 검증 및 증명 제출'}
              </button>
            </form>
          </div>

          </details>}

          <div style={{ textAlign: 'center' }}>
            <button
              type="button"
              onClick={() => { if (initialChallenge) { setError('이 창을 닫고 PACS에서 인증서 등록을 다시 시작해주세요.'); } else { operationVersion.current++; setChallenge(null); setStep(1); } }}
              style={{
                background: 'none',
                border: 'none',
                color: 'var(--text-muted, #64748b)',
                fontSize: '0.8rem',
                textDecoration: 'underline',
                cursor: 'pointer',
              }}
            >
              처음으로 돌아가기
            </button>
          </div>
        </div>
      )}

      {/* STEP 3: Verification Review & Doctor Consent */}
      {step === 3 && enrollment && (
        <div className="card" style={{ padding: '1.5rem', background: 'var(--bg-card, #ffffff)', borderRadius: '8px', border: '1px solid var(--border-color, #e2e8f0)' }} data-testid="cert-step-3">
          <h3 style={{ fontSize: '1.1rem', fontWeight: 600, marginBottom: '0.5rem' }}>
            의료인 자격 정보 및 이용 동의
          </h3>

          {(enrollment.medicalCredentialsRequired || enrollment.medicalCredentialsSubmittedAt) && <MedicalCredentialsForm
            key={enrollment.registrationId} doctor={doctor} enrollment={enrollment} onSave={async body => {
              if (!activationToken) throw new Error('인증 시간이 만료되었습니다. 다시 로그인해주세요.');
              const updated = await client.submitMedicalCredentials(activationToken, enrollment.registrationId, body);
              setEnrollment(updated);
            }} />}
          <p>인증서 확인을 마쳤습니다. 면허·자격 정보를 제출하고 이용 동의를 완료해주세요.</p>
          <details><summary>선택한 인증서 정보</summary>
          {/* Candidate Card */}
          <div
            style={{
              border: '1px solid var(--border-color, #cbd5e1)',
              borderRadius: '6px',
              padding: '1rem',
              background: 'var(--bg-subtle, #f8fafc)',
              marginBottom: '1.25rem',
              fontSize: '0.85rem',
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.75rem' }}>
              <span style={{ fontWeight: 600 }}>외부 본인확인 인증서 검증 결과</span>
              <span
                data-testid="external-ca-status"
                style={{
                  fontSize: '0.75rem',
                  fontWeight: 700,
                  padding: '2px 8px',
                  borderRadius: '10px',
                  background: enrollment.externalStatus === 'GOOD' ? 'rgba(34, 197, 94, 0.15)' : 'rgba(239, 68, 68, 0.15)',
                  color: enrollment.externalStatus === 'GOOD' ? '#15803d' : '#b91c1c',
                }}
              >
                CA 검증: {enrollment.externalStatus}
              </span>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr', gap: '0.5rem', fontSize: '0.8rem' }}>
              <div>
                <span style={{ color: 'var(--text-muted, #64748b)' }}>주체 DN: </span>
                <code style={{ fontFamily: 'monospace' }}>{enrollment.subjectDn}</code>
              </div>
              <div>
                <span style={{ color: 'var(--text-muted, #64748b)' }}>발급기관 DN: </span>
                <code style={{ fontFamily: 'monospace' }}>{enrollment.issuerDn}</code>
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.5rem' }}>
                <div>
                  <span style={{ color: 'var(--text-muted, #64748b)' }}>일련번호: </span>
                  <code style={{ fontFamily: 'monospace' }}>{enrollment.serialNumber}</code>
                </div>
                <div>
                  <span style={{ color: 'var(--text-muted, #64748b)' }}>등록 심사 상태: </span>
                  <strong style={{ color: '#d97706' }}>{enrollment.registrationStatus}</strong>
                </div>
              </div>
              <div>
                <span style={{ color: 'var(--text-muted, #64748b)' }}>인증서 전체 DER SHA-256 지문: </span>
                <code style={{ fontFamily: 'monospace', wordBreak: 'break-all' }}>{enrollment.fingerprintSha256}</code>
              </div>
            </div>
          </div>

          </details>

          {/* Consent Checkboxes (MANDATORY 3 UNCHECKED CONSENTS per defect #3) */}
          <form onSubmit={handleConsentSubmit}>
            <div style={{ border: '1px solid var(--border-color, #e2e8f0)', borderRadius: '6px', padding: '1rem', marginBottom: '1.25rem' }}>
              <h4 style={{ margin: '0 0 0.75rem 0', fontSize: '0.9rem', fontWeight: 600 }}>
                의사 전자서명 키 생성 및 서명 위탁 동의 (버전: 2026-10-13-draft)
              </h4>

              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem', fontSize: '0.85rem' }}>
                <label style={{ display: 'flex', alignItems: 'flex-start', gap: '8px', cursor: 'pointer' }}>
                  <input
                    type="checkbox"
                    data-testid="consent-checkbox-auth"
                    checked={consentAuth}
                    onChange={e => setConsentAuth(e.target.checked)}
                    style={{ marginTop: '0.15rem' }}
                  />
                  <span>
                    <strong>[필수] 외부 공인/공동/금융인증서 신원 검증 동의:</strong> 본인의 외부 인증서 공개정보(DN, 일련번호, CRL 검증 결과)를 의사 신원 확인 목적으로 시스템에 등록하는 것에 동의합니다.
                  </span>
                </label>

                <label style={{ display: 'flex', alignItems: 'flex-start', gap: '8px', cursor: 'pointer' }}>
                  <input
                    type="checkbox"
                    data-testid="consent-checkbox-sig"
                    checked={consentSig}
                    onChange={e => setConsentSig(e.target.checked)}
                    style={{ marginTop: '0.15rem' }}
                  />
                  <span>
                    <strong>[필수] 의료영상 판독문 전자서명 위탁 동의:</strong> 의료법 및 전자서명법에 따라 판독문 승인 시 OpenBao Transit 소프트웨어 키 관리 시스템에서 발행된 전용 ECDSA P-256 키로 전자서명이 자동 생성됨을 확인하고 위탁합니다.
                  </span>
                </label>

                <label style={{ display: 'flex', alignItems: 'flex-start', gap: '8px', cursor: 'pointer' }}>
                  <input
                    type="checkbox"
                    data-testid="consent-checkbox-priv"
                    checked={consentPriv}
                    onChange={e => setConsentPriv(e.target.checked)}
                    style={{ marginTop: '0.15rem' }}
                  />
                  <span>
                    <strong>[필수] 의사 면허 정보 및 서명 감사 기록 처리 동의:</strong> 판독 보고서 진위 확인 및 의료 분쟁 방지를 위해 서명 일시, 인증서 일련번호, 해시값이 감사 로그에 영구 보관됨에 동의합니다.
                  </span>
                </label>
              </div>
            </div>

            <button
              type="submit"
              data-testid="submit-consent-btn"
              disabled={loading || !consentAuth || !consentSig || !consentPriv || Boolean(enrollment.medicalCredentialsRequired && !enrollment.medicalCredentialsSubmittedAt)}
              style={{
                width: '100%',
                padding: '0.75rem',
                fontSize: '0.95rem',
                fontWeight: 600,
                background: (!consentAuth || !consentSig || !consentPriv) ? 'var(--text-muted, #94a3b8)' : 'var(--primary-color, #0284c7)',
                color: '#fff',
                border: 'none',
                borderRadius: '6px',
                cursor: (!consentAuth || !consentSig || !consentPriv || loading) ? 'not-allowed' : 'pointer',
              }}
            >
              {loading ? '위탁 동의 제출 중...' : '동의하고 등록 신청'}
            </button>
          </form>
        </div>
      )}

      {/* STEP 4: Admin Review Observation, Key Activation & Physician Signing Portal */}
      {step === 4 && (
        <div className="card" style={{ padding: '1.5rem', background: 'var(--bg-card, #ffffff)', borderRadius: '8px', border: '1px solid var(--border-color, #e2e8f0)' }} data-testid="cert-step-4">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
            <h3 style={{ margin: 0, fontSize: '1.15rem', fontWeight: 600, color: 'var(--navy-900, #0f172a)' }}>
              인증서 관리 및 판독문 서명 승인 (Certificate Management & Signing)
            </h3>
            <div style={{ display: 'flex', gap: '6px' }}>
              <button
                type="button"
                data-testid="refresh-enrollment-btn"
                onClick={handleRefreshEnrollment}
                disabled={refreshingEnrollment}
                style={{
                  padding: '4px 10px',
                  fontSize: '0.8rem',
                  border: '1px solid #cbd5e1',
                  background: '#fff',
                  borderRadius: '4px',
                  cursor: 'pointer',
                }}
              >
                {refreshingEnrollment ? '새로고침 중...' : '심사/등록 상태 새로고침'}
              </button>
              <button
                type="button"
                data-testid="cert-signout-btn"
                onClick={handleSignOut}
                style={{
                  padding: '4px 10px',
                  fontSize: '0.8rem',
                  border: '1px solid #fca5a5',
                  color: '#dc2626',
                  background: '#fff',
                  borderRadius: '4px',
                  cursor: 'pointer',
                }}
              >
                로그아웃
              </button>
            </div>
          </div>

          {/* Enrolled Certificate Information Card */}
          {enrollment && (
            <div
              data-testid="cert-activation-card"
              style={{
                border: '1px solid #cbd5e1',
                borderRadius: '6px',
                padding: '1.25rem',
                background: '#f8fafc',
                marginBottom: '1.5rem',
              }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.75rem' }}>
                <span style={{ fontWeight: 600, fontSize: '0.95rem' }}>외부 본인확인 인증서 등록 정보</span>
                <div style={{ display: 'flex', gap: '6px' }}>
                  <span
                    data-testid="enrollment-reg-status-badge"
                    style={{
                      fontSize: '0.75rem',
                      fontWeight: 700,
                      padding: '2px 8px',
                      borderRadius: '10px',
                      background: enrollment.registrationStatus === 'ACTIVE' ? '#dcfce7' : '#fef3c7',
                      color: enrollment.registrationStatus === 'ACTIVE' ? '#15803d' : '#b45309',
                    }}
                  >
                    등록 상태: {enrollment.registrationStatus}
                  </span>
                  <span
                    data-testid="enrollment-review-status-badge"
                    style={{
                      fontSize: '0.75rem',
                      fontWeight: 700,
                      padding: '2px 8px',
                      borderRadius: '10px',
                      background: enrollment.reviewStatus === 'APPROVED' ? '#dcfce7' : '#fef3c7',
                      color: enrollment.reviewStatus === 'APPROVED' ? '#15803d' : '#b45309',
                    }}
                  >
                    관리자 심사: {enrollment.reviewStatus}
                  </span>
                </div>
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: '1fr', gap: '0.4rem', fontSize: '0.8rem' }}>
                <div>
                  <span style={{ color: 'var(--text-muted, #64748b)' }}>주체 DN: </span>
                  <code style={{ fontFamily: 'monospace' }}>{enrollment.subjectDn}</code>
                </div>
                <div>
                  <span style={{ color: 'var(--text-muted, #64748b)' }}>발급기관 DN: </span>
                  <code style={{ fontFamily: 'monospace' }}>{enrollment.issuerDn}</code>
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.5rem' }}>
                  <div>
                    <span style={{ color: 'var(--text-muted, #64748b)' }}>일련번호: </span>
                    <code style={{ fontFamily: 'monospace' }}>{enrollment.serialNumber}</code>
                  </div>
                  <div>
                    <span style={{ color: 'var(--text-muted, #64748b)' }}>신원 인증 방식: </span>
                    <code>{enrollment.kind}</code>
                  </div>
                </div>
                <div>
                  <span style={{ color: 'var(--text-muted, #64748b)' }}>인증서 전체 DER 지문 (SHA-256): </span>
                  <code style={{ fontFamily: 'monospace', wordBreak: 'break-all' }}>{enrollment.fingerprintSha256}</code>
                </div>
                {enrollment.reviewedBy && (
                  <div>
                    <span style={{ color: 'var(--text-muted, #64748b)' }}>심사 완료자: </span>
                    <code>{enrollment.reviewedBy}</code> (방법: {enrollment.reviewMethod})
                  </div>
                )}
                {certificate && (
                  <div style={{ marginTop: '0.5rem', padding: '0.5rem', background: '#f0fdf4', borderRadius: '4px', border: '1px solid #86efac' }}>
                    <span style={{ color: '#15803d', fontWeight: 600 }}>발급된 서명용 키 인증서: </span>
                    <code>{certificate.serialNumber}</code> ({certificate.subject})
                  </div>
                )}
              </div>

              <div style={{ display: 'flex', gap: '8px', marginTop: '1rem', borderTop: '1px solid #e2e8f0', paddingTop: '0.75rem' }}>
                <button
                  type="button"
                  data-testid="renew-cert-btn"
                  onClick={handleStartRenewal}
                  style={{
                    padding: '4px 12px',
                    fontSize: '0.8rem',
                    border: '1px solid var(--primary-color, #0284c7)',
                    color: 'var(--primary-color, #0284c7)',
                    background: '#fff',
                    borderRadius: '4px',
                    cursor: 'pointer',
                  }}
                >
                  새 인증서로 갱신 (Renewal)
                </button>
                <button
                  type="button"
                  data-testid="unregister-cert-btn"
                  onClick={handleUnregister}
                  style={{
                    padding: '4px 12px',
                    fontSize: '0.8rem',
                    border: '1px solid #ef4444',
                    color: '#ef4444',
                    background: '#fff',
                    borderRadius: '4px',
                    cursor: 'pointer',
                  }}
                >
                  인증서 등록 해지 (Unregister)
                </button>
              </div>
            </div>
          )}

          {/* Pending Admin Review Notice */}
          {enrollment && enrollment.reviewStatus === 'PENDING' && (
            <div
              data-testid="admin-review-pending-banner"
              style={{
                padding: '1rem',
                background: 'rgba(234, 179, 8, 0.1)',
                border: '1px solid rgba(234, 179, 8, 0.4)',
                borderRadius: '6px',
                fontSize: '0.85rem',
                color: '#92400e',
                marginBottom: '1.25rem',
                lineHeight: 1.5,
              }}
            >
              <strong>⏳ 관리자 심사 대기 중:</strong><br />
              외부 인증서 등록 증명이 완료되었습니다. 본 플랫폼 보안 정책에 따라 관리자가 의사 면허 및 신원 증빙을 대조 심사(Admin Review)한 후 전자서명 키가 최종 활성화됩니다.
              관리자 승인 완료 후 상단의 [새로고침]을 클릭하십시오.
            </div>
          )}

          {/* Final Key Provisioning button if review is APPROVED but Doctor is not ACTIVE */}
          {doctor && doctor.status !== 'ACTIVE' && enrollment && enrollment.registrationStatus === 'ACTIVE' && (
            <div style={{ marginBottom: '1.5rem' }}>
              <button
                type="button"
                data-testid="activate-doctor-btn"
                disabled={loading}
                onClick={handleActivate}
                style={{
                  width: '100%',
                  padding: '0.75rem',
                  fontSize: '0.95rem',
                  fontWeight: 600,
                  background: 'var(--primary-color, #0284c7)',
                  color: '#fff',
                  border: 'none',
                  borderRadius: '6px',
                  cursor: loading ? 'not-allowed' : 'pointer',
                }}
              >
                {loading ? '전자서명 키 발급 처리 중...' : '최종 전자서명 키 생성 및 활성화 (Activate)'}
              </button>
            </div>
          )}

          {/* Physician Signing Portal Component directly in memory per Defect #5 */}
          {!initialChallenge && activationToken && doctor && (
            <PhysicianSigningPortal
              token={activationToken}
              doctor={doctor}
              onSignOut={handleSignOut}
            />
          )}
        </div>
      )}
    </div>
  );
};
