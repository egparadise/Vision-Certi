import React, { useEffect, useState } from 'react';
import type { CertificateChallenge } from '../types/api';
import type { LocalSignResult } from '../services/localCertSdkAdapter';
import { assertNoPrivateKeyOrPassword, parseCertificateChainPem } from '../utils/cryptoCert';

/** Provider-independent proof submission. No private key handling or invented signatures. */
export function ExternalCertificateProof({ challenge, busy, onProof }: {
  challenge: CertificateChallenge; busy: boolean; onProof: (proof: LocalSignResult) => Promise<void>;
}) {
  const [certificate, setCertificate] = useState('');
  const [signature, setSignature] = useState('');
  const [algorithm, setAlgorithm] = useState('SHA256withRSA');
  const [error, setError] = useState('');
  const [reading, setReading] = useState(false);
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const timer = window.setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(timer); }, []);
  const expires = Date.parse(challenge.expiresAt);
  const expired = !Number.isFinite(expires) || now >= expires;
  async function read(file: File | undefined) {
    setCertificate(''); setError('');
    if (!file) return;
    setReading(true);
    try {
      if (!/\.pem$/i.test(file.name) || file.size > 131072) throw new Error('공개 인증서 PEM 파일(128KB 이하)을 선택해 주세요.');
      const pem = await file.text();
      assertNoPrivateKeyOrPassword(pem);
      const blocks = pem.match(/-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\s]+-----END CERTIFICATE-----/g);
      if (!blocks?.length || pem.replace(/-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\s]+-----END CERTIFICATE-----/g, '').trim()) {
        throw new Error('공개 인증서 블록만 포함된 PEM 파일이 필요합니다.');
      }
      setCertificate(pem);
    } catch (e) { setError(e instanceof Error ? e.message : '인증서 파일을 읽을 수 없습니다.'); }
    finally { setReading(false); }
  }
  function download() {
    const binary = atob(challenge.signedPayloadBase64);
    const bytes = Uint8Array.from(binary, c => c.charCodeAt(0));
    const url = URL.createObjectURL(new Blob([bytes], { type: 'application/octet-stream' }));
    const a = document.createElement('a'); a.href = url; a.download = 'certificate-challenge.bin'; a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  async function submit(e: React.FormEvent) {
    e.preventDefault(); setError('');
    if (busy || reading || expired) return;
    try {
      assertNoPrivateKeyOrPassword(signature);
      const value = signature.replace(/\s/g, '');
      if (!/^[A-Za-z0-9+/]+={0,2}$/.test(value) || value.length > 16384) throw new Error('올바른 Base64 서명값을 입력해 주세요.');
      const parsed = parseCertificateChainPem(certificate);
      await onProof({ certificateDerBase64: parsed.leaf, chainDerBase64: parsed.chain,
        signatureBase64: value, signatureAlgorithm: algorithm, fingerprintSha256: '' });
    } catch (e) { setError(e instanceof Error ? e.message : '서명 검증 요청에 실패했습니다.'); }
  }
  return <section className="account-proof" aria-label="인증서 서명 결과 제출">
    <p>SDK 없이 외부에서 생성한 서명 결과를 검증하는 연결 화면입니다. 공개 인증서만 제출해서는 인증이 완료되지 않습니다.</p>
    <p>아래 요청 파일의 정확한 바이트에 대한 서명이 필요합니다. 개인키·PFX·인증서 비밀번호는 입력하지 않습니다.</p>
    <button type="button" className="enroll-secondary" disabled={expired || busy} onClick={download}>인증 요청 파일 받기</button>
    <p role="status">{expired ? '인증 요청이 만료되었습니다. 새 요청을 시작해 주세요.' : `남은 시간 ${Math.ceil((expires - now) / 1000)}초`}</p>
    <form onSubmit={submit}>
      <fieldset disabled={busy || expired} className="enroll-form-fields">
        <legend>공개 인증서와 서명 결과</legend>
        <label>공개 인증서 PEM 파일 (본인 인증서, 필요한 중간 인증서 순서)
          <input type="file" accept=".pem" onChange={e => void read(e.target.files?.[0])} disabled={reading} /></label>
        <label>서명 알고리즘<select value={algorithm} onChange={e => setAlgorithm(e.target.value)}>
          <option value="SHA256withRSA">RSA SHA-256</option><option value="SHA256withECDSA">ECDSA SHA-256</option>
        </select></label>
        <label>서명값 (Base64)<textarea value={signature} onChange={e => setSignature(e.target.value)} maxLength={16384} rows={3} required /></label>
        <button className="enroll-primary" disabled={!certificate || !signature.trim() || reading}>인증서·서명 검증</button>
      </fieldset>
    </form>
    {error && <p role="alert">{error}</p>}
  </section>;
}
