import { useCallback, useEffect, useRef, useState } from 'react';
import type { CertificateChallenge } from '../types/api';
import {
  getLocalCertSdkAdapter, localCertificateDeadline, localCertificateFingerprint,
  LocalCertificateTimeoutError, LOCAL_CERT_LIST_TIMEOUT_MS, LOCAL_CERT_READY_TIMEOUT_MS,
  LOCAL_CERT_SIGN_TIMEOUT_MS, sanitizeLocalCertificates, sanitizeLocalSignResult,
  type LocalCertificateInfo, type LocalSignResult,
} from '../services/localCertSdkAdapter';
import { computeCertificateFingerprint } from '../utils/cryptoCert';
import './certificate-security-step.css';

export interface CertificateSecurityStepProps {
  challenge: CertificateChallenge;
  onProof: (proof: LocalSignResult) => Promise<void>;
  busy?: boolean;
  onExpired?: () => void;
  onRestart?: () => void;
}

interface InstallerSettings { VITE_CERT_INSTALLER_URL?: string; VITE_CERT_INSTALLER_NAME?: string; VITE_CERT_INSTALLER_SHA256?: string }
export function certificateInstaller(settings: InstallerSettings = import.meta.env as InstallerSettings, base = window.location.href) {
  const name = settings.VITE_CERT_INSTALLER_NAME?.trim() || '공동인증서 보안프로그램';
  const raw = settings.VITE_CERT_INSTALLER_URL?.trim();
  const checksum = settings.VITE_CERT_INSTALLER_SHA256?.trim();
  if (!raw || !settings.VITE_CERT_INSTALLER_NAME?.trim() || !/^[a-fA-F0-9]{64}$/.test(checksum || '') || name.length > 100) {
    return { name, url: null };
  }
  try {
    const url = new URL(raw, base);
    const absolute = /^[a-z][a-z0-9+.-]*:/i.test(raw);
    if (url.protocol !== 'https:' || url.username || url.password || url.hash || raw.startsWith('//') ||
        (!absolute && url.origin !== new URL(base).origin)) return { name, url: null };
    return { name, url: url.href };
  } catch { return { name, url: null }; }
}

class SecurityStepError extends Error {}
function commonName(dn: string): string {
  const match = /(?:^|[,/])\s*CN\s*=\s*((?:\\.|[^,])*)/i.exec(dn);
  return match?.[1]?.replace(/\\([,=+\\])/g, '$1').trim() || dn;
}
function algorithmFor(cert: LocalCertificateInfo): string | null {
  if (/^(?:SHA256withRSA|RSA(?:-2048|-3072|-4096)?)$/i.test(cert.algorithm)) return 'SHA256withRSA';
  if (/^(?:SHA256withECDSA|EC|ECDSA|EC-P256|ECDSA-P256)$/i.test(cert.algorithm)) return 'SHA256withECDSA';
  return null;
}
function certificateCurrent(cert: LocalCertificateInfo) {
  const start = Date.parse(cert.notBefore), end = Date.parse(cert.notAfter);
  return Number.isFinite(start) && Number.isFinite(end) && start <= Date.now() && Date.now() < end;
}

export function CertificateSecurityStep({ challenge, onProof, busy = false, onExpired, onRestart }: CertificateSecurityStepProps) {
  const key = JSON.stringify(challenge);
  const latest = useRef(key);
  latest.current = key;
  const mounted = useRef(true);
  const probeId = useRef(0);
  const signing = useRef(false);
  const submitted = useRef<string | null>(null);
  const expiryNotified = useRef<string | null>(null);
  const [state, setState] = useState<'CHECKING' | 'UNAVAILABLE' | 'READY'>('CHECKING');
  const [certificates, setCertificates] = useState<LocalCertificateInfo[]>([]);
  const [selected, setSelected] = useState('');
  const [signingNow, setSigningNow] = useState(false);
  const [locked, setLocked] = useState(false);
  const [expired, setExpired] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const installer = certificateInstaller();
  const expiredNow = () => !Number.isFinite(Date.parse(challenge.expiresAt)) || Date.now() >= Date.parse(challenge.expiresAt);

  const probe = useCallback(async () => {
    const check = ++probeId.current;
    const current = key;
    const relevant = () => mounted.current && latest.current === current && check === probeId.current;
    setState('CHECKING'); setCertificates([]); setSelected(''); setError(null);
    try {
      const adapter = getLocalCertSdkAdapter();
      const ready = await localCertificateDeadline(() => adapter.isAvailable(), LOCAL_CERT_READY_TIMEOUT_MS);
      if (!relevant()) return;
      if (ready !== true) { setState('UNAVAILABLE'); return; }
      const list = sanitizeLocalCertificates(await localCertificateDeadline(() => adapter.getCertificates(), LOCAL_CERT_LIST_TIMEOUT_MS));
      if (!relevant()) return;
      setCertificates(list); setState('READY');
    } catch {
      if (relevant()) { setState('UNAVAILABLE'); setError('보안프로그램을 확인하지 못했습니다. 설치·실행 후 다시 확인해 주세요.'); }
    }
  }, [key]);

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; probeId.current++; };
  }, []);
  useEffect(() => {
    submitted.current = null; setLocked(false); setExpired(false); setError(null);
    void probe();
  }, [probe]);
  useEffect(() => {
    const check = () => {
      const end = Date.parse(challenge.expiresAt);
      if (!Number.isFinite(end) || Date.now() >= end) {
        setExpired(true);
        if (expiryNotified.current !== key) { expiryNotified.current = key; onExpired?.(); }
      }
    };
    check();
    const timer = setInterval(check, 500);
    return () => clearInterval(timer);
  }, [key, challenge.expiresAt, onExpired]);

  const submit = async () => {
    if (busy || signing.current || locked || submitted.current === key || state !== 'READY') return;
    if (expiredNow()) { setExpired(true); return; }
    const cert = certificates.find(item => item.fingerprintSha256 === selected);
    const algorithm = cert && algorithmFor(cert);
    if (!cert || !certificateCurrent(cert) || !algorithm || !challenge.allowedSignatureAlgorithms.includes(algorithm)) {
      setError('사용 가능한 공동인증서를 선택해 주세요.'); return;
    }
    const current = key;
    const relevant = () => mounted.current && latest.current === current;
    signing.current = true; setSigningNow(true); setError(null);
    try {
      const adapter = getLocalCertSdkAdapter();
      const raw = await localCertificateDeadline(() => adapter.signChallenge({
        challengeId: challenge.challengeId, challengeNonce: challenge.nonce,
        dataToSignBase64: challenge.signedPayloadBase64, targetFingerprintSha256: cert.fingerprintSha256, algorithm,
      }), Math.min(LOCAL_CERT_SIGN_TIMEOUT_MS, Math.max(1, Date.parse(challenge.expiresAt) - Date.now())));
      if (!relevant()) return;
      const proof = sanitizeLocalSignResult(raw);
      if (proof.signatureAlgorithm !== algorithm || !challenge.allowedSignatureAlgorithms.includes(proof.signatureAlgorithm)) {
        throw new SecurityStepError('선택한 인증서로 사용할 수 없는 서명 방식입니다.');
      }
      const fingerprint = await localCertificateDeadline(() => computeCertificateFingerprint(proof.certificateDerBase64), LOCAL_CERT_READY_TIMEOUT_MS);
      if (!relevant()) return;
      if (fingerprint !== cert.fingerprintSha256 || fingerprint !== localCertificateFingerprint(proof.fingerprintSha256)) {
        throw new SecurityStepError('선택한 인증서와 서명에 사용된 인증서가 다릅니다. 다시 확인해 주세요.');
      }
      if (expiredNow() || !certificateCurrent(cert)) { setExpired(true); throw new SecurityStepError('인증 시간이 만료되었습니다. 인증을 다시 시작해 주세요.'); }
      submitted.current = current; setLocked(true);
      await onProof(proof);
    } catch (failure) {
      if (relevant()) {
        if (failure instanceof LocalCertificateTimeoutError) setLocked(true);
        setError(failure instanceof SecurityStepError || failure instanceof LocalCertificateTimeoutError
          ? failure.message : '인증서 확인을 완료하지 못했습니다. 보안프로그램 상태를 확인한 뒤 다시 진행해 주세요.');
      }
    } finally { signing.current = false; if (mounted.current) setSigningNow(false); }
  };

  const disabled = busy || signingNow || expired || locked;
  const action = challenge.purpose === 'CERTIFICATE_LOGIN' ? '공동인증서로 로그인' : '공동인증서 등록';
  return <section className="vc-security-step" aria-label="공동인증서 보안프로그램">
    <header><span className="vc-security-icon" aria-hidden="true">✓</span><div>
      <h2>공동인증서 보안프로그램</h2><p>인증서는 PC의 보안프로그램에서 선택하고 확인합니다.</p>
    </div></header>
    <div className="vc-security-install">
      <div><strong>{installer.name}</strong><span role="status" className={`vc-security-state vc-security-${state.toLowerCase()}`}>
        {state === 'CHECKING' ? '설치 상태 확인 중' : state === 'READY' ? '연결됨' : '설치 또는 실행 확인 필요'}
      </span></div>
      <div className="vc-security-actions">
        {installer.url ? <a className="vc-security-button" href={installer.url} target="_blank" rel="noopener noreferrer" download>보안프로그램 설치</a>
          : <button className="vc-security-button" disabled>설치파일 연결 준비 중</button>}
        <button className="vc-security-button secondary" disabled={state === 'CHECKING' || disabled} onClick={() => void probe()}>설치 상태 다시 확인</button>
      </div>
    </div>
    {state === 'UNAVAILABLE' && <p className="vc-security-help">보안프로그램을 설치한 후 실행하고 ‘설치 상태 다시 확인’을 눌러 주세요.
      {!installer.url && ' 설치파일이 아직 연결되지 않았습니다.'}</p>}
    {state === 'READY' && <fieldset disabled={disabled}>
      <legend>사용할 공동인증서 선택</legend>
      {certificates.length === 0 && <p>사용 가능한 인증서를 찾지 못했습니다. PC 보안프로그램에서 인증서를 확인해 주세요.</p>}
      {certificates.map(cert => {
        const algorithm = algorithmFor(cert);
        const usable = certificateCurrent(cert) && algorithm !== null && challenge.allowedSignatureAlgorithms.includes(algorithm);
        return <label className={`vc-security-certificate ${selected === cert.fingerprintSha256 ? 'selected' : ''}`} key={cert.fingerprintSha256}>
          <input type="radio" name={`certificate-${challenge.challengeId}`} checked={selected === cert.fingerprintSha256}
            disabled={!usable} onChange={() => setSelected(cert.fingerprintSha256)} />
          <span><strong>{commonName(cert.subjectDn)}</strong><span>발급기관: {commonName(cert.issuerDn)}</span>
            <span>만료일: {Number.isFinite(Date.parse(cert.notAfter)) ? new Date(cert.notAfter).toLocaleDateString('ko-KR') : '확인 불가'}{!usable && ' · 사용할 수 없음'}</span></span>
        </label>;
      })}
    </fieldset>}
    <p className="vc-security-help">인증서 비밀번호는 외부 보안프로그램에서만 입력합니다. 이 웹페이지는 개인키나 비밀번호를 받지 않습니다.</p>
    {expired && <p role="alert">인증 시간이 만료되었습니다. 인증을 다시 시작해 주세요.</p>}
    {error && <p role="alert">{error}</p>}
    <button className="vc-security-submit" disabled={disabled || state !== 'READY' || !selected} onClick={() => void submit()}>
      {signingNow || busy ? '인증서 확인 중…' : action}
    </button>
    {(expired || (locked && error)) && onRestart && <button className="vc-security-button secondary" disabled={busy || signingNow} onClick={onRestart}>인증 다시 시작</button>}
  </section>;
}

export default CertificateSecurityStep;
