import React, { useEffect, useState } from 'react';
import type { CertificateChallenge } from '../types/api';
import type { LocalSignResult } from '../services/localCertSdkAdapter';
import { signWithCertificateFiles, type CertificateFiles } from '../services/certificateFileSigner';

/** The server-supplied exact bytes are signed locally; only proof fields reach INVIZ. */
export function ExternalCertificateProof({ challenge, payloadBase64, expiresAt, actionLabel, busy, onProof }: {
  challenge?: CertificateChallenge; payloadBase64?: string; expiresAt?: string; actionLabel?: string;
  busy: boolean; onProof: (proof: LocalSignResult) => Promise<void>;
}) {
  const [kind, setKind] = useState<'PFX' | 'NPKI'>('PFX');
  const [bundle, setBundle] = useState<File>();
  const [certificate, setCertificate] = useState<File>();
  const [privateKey, setPrivateKey] = useState<File>();
  const [chain, setChain] = useState<File>();
  const [password, setPassword] = useState('');
  const [signing, setSigning] = useState(false);
  const [error, setError] = useState('');
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const timer = window.setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(timer); }, []);
  const expires = Date.parse(challenge?.expiresAt ?? expiresAt ?? '');
  const expired = !Number.isFinite(expires) || now >= expires;

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    const form = event.currentTarget as HTMLFormElement;
    setError('');
    if (busy || signing || expired) return;
    try {
      let files: CertificateFiles;
      if (kind === 'PFX') {
        if (!bundle) throw new Error('PFX/P12 인증서 파일을 선택해 주세요.');
        files = { kind, bundle };
      } else {
        if (!certificate || !privateKey) throw new Error('signCert.der와 signPri.key를 모두 선택해 주세요.');
        files = { kind, certificate, privateKey, chain };
      }
      setSigning(true);
      const toSign = challenge?.signedPayloadBase64 ?? payloadBase64;
      if (!toSign) throw new Error('서명할 인증 요청이 없습니다. 다시 시도해 주세요.');
      const proof = await signWithCertificateFiles(files, password, toSign);
      setPassword('');
      await onProof(proof);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '인증서 서명에 실패했습니다.');
    } finally {
      setSigning(false); setPassword(''); setBundle(undefined); setCertificate(undefined);
      setPrivateKey(undefined); setChain(undefined);
      form.querySelectorAll<HTMLInputElement>('input[type="file"]').forEach(input => { input.value = ''; });
    }
  }

  return <section className="account-proof" aria-label={actionLabel ?? '공동인증서 선택 및 로그인'}>
    <p>의사 PC의 공동인증서를 선택하고 비밀번호를 입력해 주세요. 개인키 파일과 비밀번호는 서버로 전송되지 않습니다.</p>
    <p role="status">{expired ? '인증 요청이 만료되었습니다. 새 요청을 시작해 주세요.' : `남은 시간 ${Math.ceil((expires - now) / 1000)}초`}</p>
    <form onSubmit={submit}>
      <fieldset disabled={busy || signing || expired} className="enroll-form-fields">
        <legend>공동인증서</legend>
        <label>파일 형식<select value={kind} onChange={event => setKind(event.target.value as 'PFX' | 'NPKI')}>
          <option value="PFX">PFX / P12</option><option value="NPKI">NPKI (signCert.der + signPri.key)</option>
        </select></label>
        {kind === 'PFX' ? <label>PFX/P12 파일<input type="file" accept=".pfx,.p12,application/x-pkcs12" onChange={event => setBundle(event.target.files?.[0])} required /></label> : <>
          <label>공개 인증서 (signCert.der)<input type="file" accept=".der" onChange={event => setCertificate(event.target.files?.[0])} required /></label>
          <label>암호화된 개인키 (signPri.key)<input type="file" accept=".key" onChange={event => setPrivateKey(event.target.files?.[0])} required /></label>
          <label>중간 인증기관 인증서 (있는 경우)<input type="file" accept=".der" onChange={event => setChain(event.target.files?.[0])} /></label>
        </>}
        <label>인증서 비밀번호<input type="password" autoComplete="off" value={password} onChange={event => setPassword(event.target.value)} required /></label>
        <button className="enroll-primary" disabled={!password || signing}>{signing ? '인증서 확인 중…' : actionLabel ?? '인증서로 계속'}</button>
      </fieldset>
    </form>
    {error && <p role="alert">{error}</p>}
  </section>;
}
