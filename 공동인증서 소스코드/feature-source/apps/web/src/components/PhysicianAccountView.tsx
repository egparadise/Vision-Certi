import React, { useEffect, useRef, useState } from 'react';
import { useApp } from '../context/AppContext';
import type { PhysicianAccountConfig, PhysicianAccountReceipt } from '../types/physicianAccount';
import type { CertificateChallenge } from '../types/api';
import { CertificateEnrollmentView } from './CertificateEnrollmentView';
import './registration.css';
import './physician-account.css';

const initial = { username: '', password: '', confirmation: '', name: '', phone: '', licenseNumber: '', centerName: '' };
export function PhysicianAccountView() {
  const { client } = useApp();
  const [config, setConfig] = useState<PhysicianAccountConfig | null>(null);
  const [mode, setMode] = useState<'register' | 'resume'>('register');
  const [form, setForm] = useState(initial);
  const [consent, setConsent] = useState(false);
  const [receipt, setReceipt] = useState<PhysicianAccountReceipt | null>(null);
  const [challenge, setChallenge] = useState<CertificateChallenge | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const version = useRef(0);
  useEffect(() => { let live = true; client.getPhysicianAccountConfig().then(r => { if (live) setConfig(r); })
    .catch(e => { if (live) setError(e instanceof Error ? e.message : '가입 설정을 확인할 수 없습니다.'); });
    return () => { live = false; version.current++; }; }, [client]);
  async function submit(e: React.FormEvent) {
    e.preventDefault(); if (busy || !config?.available) return;
    setError('');
    if (mode === 'register' && form.password !== form.confirmation) { setError('비밀번호 확인이 일치하지 않습니다.'); return; }
    const operation = version.current; setBusy(true);
    try {
      const result = mode === 'register'
        ? await client.registerPhysicianAccount({ username: form.username, password: form.password, name: form.name,
          phone: form.phone, licenseNumber: form.licenseNumber, centerName: form.centerName,
          privacyConsent: consent, consentVersion: config.consentVersion })
        : await client.resumePhysicianAccount(form.username, form.password);
      if (version.current !== operation) return;
      setReceipt(result); setForm(initial); setConsent(false);
    } catch (e) { if (version.current === operation) setError(e instanceof Error ? e.message : '가입 요청에 실패했습니다.'); }
    finally { if (version.current === operation) setBusy(false); }
  }
  async function start() {
    if (!receipt || busy) return;
    setBusy(true); setError('');
    try { const result = await client.createPhysicianAccountChallenge(receipt.enrollmentToken); setChallenge(result); }
    catch (e) { setError(e instanceof Error ? e.message : '인증 요청을 시작하지 못했습니다.'); }
    finally { setBusy(false); }
  }
  async function logout() {
    if (!receipt || busy) return;
    const token = receipt.enrollmentToken;
    // Immediately unmount certificate proof/session views and clear local credentials.
    version.current++; setReceipt(null); setChallenge(null); setForm(initial); setConsent(false); setError('');
    try { await client.logoutPhysicianAccount(token); }
    catch { setError('화면에서 로그아웃했습니다. 서버 세션 종료를 확인하지 못했으며 기존 가입 세션은 만료 시 종료됩니다.'); }
  }
  const input = (key: keyof typeof initial, label: string, type = 'text', max = 100) => <label key={key}>{label}
    <input required value={form[key]} type={type} maxLength={max}
      autoComplete={key === 'username' ? 'username' : type === 'password' ? (mode === 'resume' ? 'current-password' : 'new-password') : key === 'name' ? 'name' : key === 'phone' ? 'tel' : 'off'}
      minLength={key === 'password' && mode === 'register' ? 12 : undefined}
      onChange={e => setForm(previous => ({ ...previous, [key]: e.target.value }))} /></label>;
  return <main className="physician-account-page">
    <header><a href="#/login">INVIZ 인증센터</a><a href="#/physician/login">공동인증서 로그인</a></header>
    <h1>원격판독의사 가입</h1>
    <p>직접 가입 → 공동인증서 확인 → 의료인 자격·동의 → 검토·활성화</p>
    {config && !config.available && <p role="status">현재 의사 직접 가입이 설정되지 않았습니다.</p>}
    {error && <p role="alert" className="account-error">{error}</p>}
    {!receipt ? <section className="account-card">
      <nav aria-label="가입 절차"><button disabled={busy} aria-pressed={mode === 'register'} onClick={() => { setMode('register'); setForm(initial); setConsent(false); setError(''); }}>신규 가입</button>
        <button disabled={busy} aria-pressed={mode === 'resume'} onClick={() => { setMode('resume'); setForm(initial); setConsent(false); setError(''); }}>가입 이어하기</button></nav>
      <h2>{mode === 'register' ? '가입 정보' : '기존 가입 정보 확인'}</h2>
      <p>ID·비밀번호는 가입 절차를 이어갈 때 사용합니다. 판독·서명 로그인에는 공동인증서가 필요합니다.</p>
      <form onSubmit={submit}><fieldset disabled={busy || !config?.available} className="enroll-form-fields">
        <legend className="enroll-sr-only">의사 계정 정보</legend>
        {input('username', 'ID (영문으로 시작하는 4~32자)', 'text', 32)}
        {input('password', '비밀번호 (12~128자)', 'password', 128)}
        {mode === 'register' && <>
          {input('confirmation', '비밀번호 확인', 'password', 128)}
          {input('name', '이름', 'text', 50)}{input('phone', '연락처 (010 휴대폰 번호)', 'tel', 13)}
          {input('licenseNumber', '의사면허번호', 'text', 20)}{input('centerName', '소속 기관', 'text', 100)}
          <p>전문의 자격번호·전문과목은 인증서 확인 후 제출합니다. 연락처는 입력 정보이며 본인확인 완료로 처리하지 않습니다.</p>
          <label className="account-consent"><input type="checkbox" checked={consent} onChange={e => setConsent(e.target.checked)} required />가입·계정 관리와 의료인 자격 검토를 위한 입력 정보 수집·이용에 동의합니다.</label>
        </>}
        <button className="enroll-primary" disabled={mode === 'register' && !consent}>{busy ? '처리 중…' : mode === 'register' ? '가입하고 인증서 등록 준비' : '가입 절차 이어하기'}</button>
      </fieldset></form>
    </section> : <>
      <section className="account-card">
        <h2>{receipt.account.doctor.name}님의 계정</h2>
        <p>ID: {receipt.account.username} · 접속 시 계정 상태: {receipt.account.doctor.status}</p>
        <p>이 접속은 가입 절차 전용입니다. 공동인증서 확인과 의료인 자격 검토·동의 후 판독·서명을 이용할 수 있습니다.</p>
        <div className="account-actions">
          {receipt.account.doctor.status !== 'ACTIVE' && <button className="enroll-primary" disabled={busy} onClick={() => { setChallenge(null); void start(); }}>{busy ? '요청 중…' : '인증서 등록·재개 요청'}</button>}
          <a href="#/physician/login">등록 인증서로 로그인</a><button disabled={busy} onClick={() => void logout()}>가입 세션 종료</button>
        </div>
      </section>
      {challenge && <CertificateEnrollmentView key={challenge.challengeId} initialChallenge={challenge} proofMethod="external" />}
    </>}
  </main>;
}
