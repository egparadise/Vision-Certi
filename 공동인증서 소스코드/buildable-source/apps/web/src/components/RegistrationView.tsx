import React, { useEffect, useRef, useState } from 'react';
import { ArrowLeft, ArrowRight, Check, CheckCircle2, ShieldCheck, Smartphone, Stethoscope, UserRound } from 'lucide-react';
import './registration.css';

type AccountType = 'PERSONAL' | 'CLINICIAN';
type Challenge = { challengeId: string; expiresAt: string; resendAfter: string; mode: string; devCode?: string };
type Proof = { verificationToken: string; expiresAt: string; mode: string };
type Receipt = { registrationId: string; username: string; accountType: AccountType; status: string; phoneVerification: string; registeredAt: string };
const consentVersion = '2026-10-13-draft';
const initialForm = { username: '', password: '', confirmPassword: '', name: '', phone: '', email: '', institution: '', licenseNumber: '', specialty: '' };
const consentItems = [
  { key: 'authentication', title: '전자인증 서비스 가입 동의', text: '계정 가입과 인증 수단 등록을 신청합니다. 입력한 휴대폰 번호는 가입 확인에 사용합니다. 번호 확인만으로 실명이나 의료인 자격이 확인되는 것은 아닙니다. 현재 개발 환경에서는 실제 문자 발송과 본인확인이 수행되지 않습니다.' },
  { key: 'signature', title: '전자서명 서비스 가입 동의', text: '전자서명 서비스 이용을 신청합니다. 서비스 가입 동의는 개별 문서의 서명 동의가 아니며, 문서마다 내용을 확인하고 별도로 승인해야 합니다. 의료인의 실제 서명 권한은 자격과 소속 확인 후 부여되어야 합니다. 현재 개발 환경에서는 실제 인증서가 발급되지 않습니다.' },
  { key: 'privacy', title: '가입 정보 수집·이용 동의', text: '가입 신청을 처리하기 위해 ID, 비밀번호의 단방향 해시, 이름, 휴대폰 번호와 동의 이력을 저장합니다. 이메일은 선택 사항입니다. 의료인은 소속 의료기관, 면허번호, 진료과도 입력합니다. 이 개발 버전은 서버 메모리에만 저장하며 재시작 시 초기화됩니다. 실제 개인정보를 입력하지 마세요. 운영용 약관과 보유 기간은 별도 확정이 필요합니다.' },
] as const;

async function enrollmentRequest<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(`/v1/enrollment${path}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  });
  const data = await response.json().catch(() => null);
  if (!response.ok) throw new Error(data?.message || '요청을 처리하지 못했습니다. 잠시 후 다시 시도해주세요.');
  return data as T;
}

export function RegistrationView() {
  const [accountType, setAccountType] = useState<AccountType>('PERSONAL');
  const [step, setStep] = useState(0);
  const [consents, setConsents] = useState({ authentication: false, signature: false, privacy: false });
  const [form, setForm] = useState(initialForm);
  const [challenge, setChallenge] = useState<Challenge | null>(null);
  const [proof, setProof] = useState<Proof | null>(null);
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState<'send' | 'verify' | 'submit' | null>(null);
  const [error, setError] = useState('');
  const [phoneMessage, setPhoneMessage] = useState('');
  const [receipt, setReceipt] = useState<Receipt | null>(null);
  const [now, setNow] = useState(Date.now());
  const phoneGeneration = useRef(0);
  const mounted = useRef(true);
  const heading = useRef<HTMLHeadingElement>(null);
  const pending = useRef(false);
  useEffect(() => {
    mounted.current = true;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => { mounted.current = false; window.clearInterval(timer); };
  }, []);
  useEffect(() => { heading.current?.focus(); }, [step]);
  const phone = form.phone.replace(/[\s-]/g, '');
  const allAgreed = Object.values(consents).every(Boolean);
  const verified = Boolean(proof && new Date(proof.expiresAt).getTime() > now);
  const cooldown = challenge ? Math.max(0, Math.ceil((new Date(challenge.resendAfter).getTime() - now) / 1000)) : 0;
  const remaining = challenge ? Math.max(0, Math.ceil((new Date(challenge.expiresAt).getTime() - now) / 1000)) : 0;
  const clinician = accountType === 'CLINICIAN';

  function changeField(key: keyof typeof initialForm, value: string) {
    setForm(previous => ({ ...previous, [key]: value }));
    if (key === 'phone' && value.replace(/[\s-]/g, '') !== phone) {
      phoneGeneration.current++;
      setChallenge(null); setProof(null); setCode(''); setPhoneMessage('번호를 변경했습니다. 다시 인증해주세요.');
    }
  }

  async function sendCode() {
    if (pending.current) return;
    if (!/^010\d{8}$/.test(phone)) { setError('휴대폰 번호를 010으로 시작하는 11자리로 입력해주세요.'); return; }
    pending.current = true; setBusy('send'); setError(''); setProof(null); setCode('');
    const generation = phoneGeneration.current;
    try {
      const result = await enrollmentRequest<Challenge>('/phone-challenges', { phone });
      if (!mounted.current || generation !== phoneGeneration.current) return;
      setChallenge(result); setNow(Date.now());
      setPhoneMessage(result.mode === 'MOCK_SMS' ? '테스트 인증번호가 준비되었습니다. 아래 번호를 입력해주세요. 실제 문자는 발송되지 않습니다.' : '입력한 휴대폰으로 인증번호를 발송했습니다.');
    } catch (err) {
      if (mounted.current && generation === phoneGeneration.current) setError(err instanceof Error ? err.message : '인증번호 요청에 실패했습니다.');
    } finally { pending.current = false; if (mounted.current) setBusy(null); }
  }

  async function verifyCode() {
    if (pending.current || !challenge) return;
    if (!/^\d{6}$/.test(code)) { setError('6자리 인증번호를 입력해주세요.'); return; }
    pending.current = true; setBusy('verify'); setError('');
    const generation = phoneGeneration.current;
    try {
      const result = await enrollmentRequest<Proof>(`/phone-challenges/${encodeURIComponent(challenge.challengeId)}/verify`, { code });
      if (!mounted.current || generation !== phoneGeneration.current) return;
      setProof(result); setCode(''); setNow(Date.now());
      setPhoneMessage(result.mode === 'MOCK_SMS' ? '테스트 번호 확인 완료 · 실제 휴대폰 본인인증이 아닙니다.' : '휴대폰 번호 확인을 완료했습니다.');
    } catch (err) {
      if (mounted.current && generation === phoneGeneration.current) setError(err instanceof Error ? err.message : '인증번호 확인에 실패했습니다.');
    } finally { pending.current = false; if (mounted.current) setBusy(null); }
  }

  async function register(event: React.FormEvent) {
    event.preventDefault();
    if (pending.current) return;
    setError('');
    if (!allAgreed) { setError('필수 가입 동의를 모두 확인해주세요.'); return; }
    if (form.password !== form.confirmPassword) { setError('비밀번호와 비밀번호 확인이 일치하지 않습니다.'); return; }
    if (!proof || Date.parse(proof.expiresAt) <= Date.now()) { setError('휴대폰 인증을 완료해주세요. 인증이 만료되었다면 다시 진행해주세요.'); return; }
    pending.current = true; setBusy('submit');
    try {
      const result = await enrollmentRequest<Receipt>('/registrations', {
        accountType, username: form.username.trim(), password: form.password, name: form.name.trim(), phone,
        email: form.email.trim() || null,
        institution: clinician ? form.institution.trim() : null,
        licenseNumber: clinician ? form.licenseNumber.trim() : null,
        specialty: clinician ? form.specialty.trim() : null,
        verificationToken: proof.verificationToken, consents: { ...consents, version: consentVersion },
      });
      if (!mounted.current) return;
      setReceipt(result); setForm({ ...initialForm }); setProof(null); setChallenge(null); setCode(''); setStep(3);
    } catch (err) {
      if (mounted.current) setError(err instanceof Error ? err.message : '가입 처리에 실패했습니다.');
    } finally { pending.current = false; if (mounted.current) setBusy(null); }
  }

  const input = (key: keyof typeof initialForm, label: string, options: { type?: string; required?: boolean; autoComplete?: string; hint?: string; pattern?: string; minLength?: number; maxLength?: number; placeholder?: string } = {}) => (
    <div className="enroll-field">
      <label htmlFor={`signup-${key}`}>{label} {options.required !== false ? <span className="enroll-required">필수</span> : <span className="enroll-optional">선택</span>}</label>
      <input id={`signup-${key}`} name={key} type={options.type || 'text'} value={form[key]} onChange={e => changeField(key, e.target.value)}
        required={options.required !== false} autoComplete={options.autoComplete} pattern={options.pattern} minLength={options.minLength} maxLength={options.maxLength}
        placeholder={options.placeholder} aria-describedby={options.hint ? `hint-${key}` : undefined} />
      {options.hint && <p id={`hint-${key}`} className="enroll-hint">{options.hint}</p>}
    </div>
  );

  return <div className="enrollment-page">
    <header className="enroll-header">
      <a href="#/signup" className="enroll-brand"><ShieldCheck size={25} /> <span>Vision<span className="enroll-brand-accent">Certi</span></span></a>
      <div style={{ display: 'flex', gap: '12px', alignItems: 'center' }}>
        <a href="#/activate" className="enroll-console-link">의사 활성화</a>
        <a href="#/doctors" className="enroll-console-link">의사 관리 (Admin)</a>
        <a href="#/" className="enroll-console-link">관리 화면으로 <ArrowRight size={14} /></a>
      </div>
    </header>
    <main className="enroll-layout">
      <aside className="enroll-intro">
        <span className="enroll-eyebrow">INVIZ · ELECTRONIC TRUST</span>
        <h1>믿을 수 있는 시작,<br /><span>나의 전자서명.</span></h1>
        <p>전자인증과 전자서명 서비스의<br />가입 신청을 한 곳에서 진행하세요.</p>
        <div className="enroll-intro-points"><div><ShieldCheck /> 가입 동의는 명확하게</div><div><Smartphone /> 휴대폰 번호 확인은 필수로</div><div><Stethoscope /> 의료인 자격은 별도 확인</div></div>
        <div className="enroll-dev-note"><strong>개발 시험 화면</strong><p>현재 인증은 테스트 방식입니다. 실제 문자·본인확인·인증서 발급은 수행하지 않습니다. 합성 정보로만 시험해주세요.</p></div>
      </aside>
      <section className="enroll-panel" aria-label="서비스 가입">
        <ol className="enroll-steps" aria-label="가입 진행 단계">{['가입 유형', '서비스 동의', '정보 입력', '신청 완료'].map((label, index) => <li key={label} className={step === index ? 'active' : step > index ? 'done' : ''} aria-current={step === index ? 'step' : undefined}><span>{step > index ? <Check size={13} /> : index + 1}</span>{label}</li>)}</ol>
        <div className="enroll-panel-body">
          {step === 0 && <>
            <p className="enroll-section-label">01 / ACCOUNT TYPE</p><h2 ref={heading} tabIndex={-1}>어떤 유형으로 가입하시나요?</h2><p className="enroll-description">개인과 의료인에게 필요한 가입 항목을 안내합니다.</p>
            <div className="enroll-type-options" role="radiogroup" aria-label="가입 유형">
              {([{ type: 'PERSONAL', title: '개인', subtitle: '개인 전자인증 · 전자서명 서비스', icon: <UserRound size={25} /> }, { type: 'CLINICIAN', title: '의료인 (판독의)', subtitle: 'PACS 계정 확인 후 공동인증서 직접 등록', icon: <Stethoscope size={25} /> }] as const).map(item => <label key={item.type} className={`enroll-type ${accountType === item.type ? 'selected' : ''}`}><input type="radio" name="accountType" value={item.type} checked={accountType === item.type} onChange={() => setAccountType(item.type)} /><span className="enroll-type-icon">{item.icon}</span><span><strong>{item.title}</strong><small>{item.subtitle}</small></span><span className="enroll-radio-mark">{accountType === item.type && <Check size={14} />}</span></label>)}
            </div>
            {accountType === 'CLINICIAN' ? (
              <div className="enroll-clinician-guide" style={{ marginTop: '16px', padding: '16px', backgroundColor: 'var(--navy-50, #f8fafc)', borderRadius: '8px', border: '1px solid var(--teal-500, #14b8a6)' }} data-testid="clinician-activation-notice">
                <h3 style={{ fontSize: '0.95rem', color: 'var(--navy-900, #0f172a)', marginBottom: '8px', display: 'flex', alignItems: 'center', gap: '6px' }}>
                  <Stethoscope size={18} style={{ color: 'var(--teal-700, #0f766e)' }} />
                  판독의사가 직접 가입하고 공동인증서를 등록합니다
                </h3>
                <p style={{ fontSize: '0.85rem', color: 'var(--navy-700, #334155)', lineHeight: '1.5', marginBottom: '12px' }}>
                  INVIZ에 직접 가입한 뒤 인증서 검증, 이용 동의 및 신원·면허 연결 검토를 진행합니다. <strong>관리자 초대코드는 필요하지 않으며, 등록 후에도 매번 공동인증서로 로그인합니다.</strong>
                </p>
                <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
                  <a href="#/physician/register" className="enroll-primary" style={{ textDecoration: 'none', display: 'inline-flex', alignItems: 'center', gap: '6px' }} data-testid="link-to-doctor-activation">
                    의사 직접 가입·인증서 등록 <ArrowRight size={16} />
                  </a>
                  <a href="#/doctors" className="enroll-secondary" style={{ textDecoration: 'none', display: 'inline-flex', alignItems: 'center', gap: '6px' }} data-testid="link-to-doctor-admin">
                    관리자 인증서 검토·계정 관리
                  </a>
                </div>
              </div>
            ) : (
              <>
                <p className="enroll-hint">개인 전자인증 및 전자서명 가입 절차를 진행합니다.</p>
                <button className="enroll-primary" onClick={() => { setStep(1); setError(''); }} data-testid="goto-personal-consent-btn">
                  동의 항목 확인하기 <ArrowRight size={17} />
                </button>
              </>
            )}
          </>}
          {step === 1 && <>
            <p className="enroll-section-label">02 / CONSENT</p><h2 ref={heading} tabIndex={-1}>{clinician ? '의료인' : '개인'} 서비스 가입 동의</h2><p className="enroll-description">각 내용을 확인한 뒤 동의해주세요. 모든 항목은 필수입니다.</p>
            <label className="enroll-agree-all"><input type="checkbox" checked={allAgreed} onChange={e => setConsents({ authentication: e.target.checked, signature: e.target.checked, privacy: e.target.checked })} /> 필수 항목 전체 동의</label>
            <div className="enroll-consents">{consentItems.map(item => <div className="enroll-consent" key={item.key}><label><input type="checkbox" checked={consents[item.key]} onChange={e => setConsents(previous => ({ ...previous, [item.key]: e.target.checked }))} /><span><small>필수</small> {item.title}</span></label><details><summary>{item.title} 내용 보기</summary><p>{item.text}</p></details></div>)}</div>
            <p className="enroll-hint">개발용 동의문 초안 · {consentVersion} · 운영용 약관이 아닙니다.</p><div className="enroll-actions"><button className="enroll-secondary" onClick={() => setStep(0)}><ArrowLeft size={16} /> 이전</button><button className="enroll-primary" disabled={!allAgreed} onClick={() => { setStep(2); setError(''); }}>동의하고 정보 입력 <ArrowRight size={17} /></button></div>
          </>}
          {step === 2 && <>
            <p className="enroll-section-label">03 / {clinician ? 'CLINICIAN' : 'PERSONAL'} INFORMATION</p><h2 ref={heading} tabIndex={-1}>{clinician ? '의료인(판독의)' : '개인'} 가입 정보</h2><p className="enroll-description">계정 정보와 휴대폰 인증을 완료해주세요.</p>
            <form onSubmit={register}>
              <fieldset disabled={busy === 'submit'} className="enroll-form-fields"><legend className="enroll-sr-only">가입 정보</legend>
                {input('username', 'ID', { autoComplete: 'username', minLength: 4, maxLength: 32, pattern: '[A-Za-z][A-Za-z0-9_.\\-]{3,31}', hint: '영문으로 시작하는 4~32자 · 영문, 숫자, 밑줄, 점, 하이픈', placeholder: '사용할 ID를 입력하세요' })}
                <div className="enroll-field-grid">{input('password', '비밀번호', { type: 'password', autoComplete: 'new-password', minLength: 12, maxLength: 128, hint: '12~128자. 다른 서비스에서 사용하지 않는 비밀번호' })}{input('confirmPassword', '비밀번호 확인', { type: 'password', autoComplete: 'new-password', minLength: 12, maxLength: 128 })}</div>
                {input('name', '이름', { autoComplete: 'name', minLength: 2, maxLength: 50 })}
                <section className={`enroll-phone ${verified ? 'verified' : ''}`} aria-label="휴대폰 인증">
                  <div className="enroll-phone-heading"><Smartphone size={17} /><h3>휴대폰 인증</h3><span className="enroll-required">필수</span></div>
                  <label htmlFor="signup-phone">휴대폰 번호</label><div className="enroll-input-action"><input id="signup-phone" name="phone" type="tel" autoComplete="tel-national" inputMode="tel" required value={form.phone} onChange={e => changeField('phone', e.target.value)} maxLength={15} placeholder="010-0000-0000" /><button type="button" className="enroll-secondary" onClick={sendCode} disabled={Boolean(busy) || cooldown > 0}>{busy === 'send' ? '요청 중…' : cooldown > 0 ? `${cooldown}초 후 재요청` : challenge ? '인증번호 재요청' : '인증번호 요청'}</button></div>
                  {challenge && !verified && <><label htmlFor="signup-otp" className="enroll-otp-label">인증번호</label><div className="enroll-input-action"><input id="signup-otp" inputMode="numeric" autoComplete="one-time-code" value={code} onChange={e => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))} maxLength={6} placeholder="6자리 숫자" /><button type="button" className="enroll-secondary" onClick={verifyCode} disabled={Boolean(busy) || code.length !== 6 || remaining === 0}>{busy === 'verify' ? '확인 중…' : '인증번호 확인'}</button></div><p className="enroll-hint">{remaining > 0 ? `유효시간 ${Math.floor(remaining / 60)}분 ${remaining % 60}초 · 5회 오류 시 다시 요청해주세요.` : '인증번호가 만료되었습니다. 다시 요청해주세요.'}</p></>}
                  {phoneMessage && <p className="enroll-phone-message" role="status">{verified && <CheckCircle2 size={16} />}{phoneMessage}</p>}
                  {proof && !verified && <p role="status" className="enroll-hint">인증 확인이 만료되었습니다. 인증번호를 다시 요청해주세요.</p>}
                  {challenge?.mode === 'MOCK_SMS' && challenge.devCode && !verified && <div className="enroll-test-code">실제 문자 미발송 · 개발용 인증번호 <strong data-testid="dev-otp">{challenge.devCode}</strong></div>}
                </section>
                {input('email', '이메일', { type: 'email', required: false, autoComplete: 'email', maxLength: 254, placeholder: 'name@example.com' })}
                {clinician && <section className="enroll-clinician"><h3><Stethoscope size={18} /> 의료인 정보</h3><p className="enroll-hint">가입 신청만으로 면허가 확인되거나 판독문 서명 권한이 부여되지 않습니다.</p>{input('institution', '소속 의료기관', { minLength: 2, maxLength: 100, autoComplete: 'organization' })}<div className="enroll-field-grid">{input('licenseNumber', '의사 면허번호', { pattern: '[0-9]{1,20}', maxLength: 20, hint: '숫자만 입력' })}{input('specialty', '진료과', { minLength: 2, maxLength: 50, placeholder: '예: 영상의학과' })}</div></section>}
              </fieldset>
              {error && <div className="enroll-error" role="alert">{error}</div>}
              <div className="enroll-actions"><button type="button" className="enroll-secondary" disabled={Boolean(busy)} onClick={() => { setStep(1); setError(''); }}><ArrowLeft size={16} /> 이전</button><button type="submit" className="enroll-primary" disabled={Boolean(busy) || !verified || !allAgreed}>{busy === 'submit' ? '신청 처리 중…' : clinician ? '의료인 가입 신청' : '개인 가입 신청'}<ArrowRight size={17} /></button></div>
              {!verified && <p className="enroll-hint enroll-center">휴대폰 인증을 완료하면 가입을 신청할 수 있습니다.</p>}
            </form>
          </>}
          {step === 3 && receipt && <div className="enroll-success"><span className="enroll-success-icon"><CheckCircle2 size={42} /></span><p className="enroll-section-label">APPLICATION RECEIVED</p><h2 ref={heading} tabIndex={-1}>{receipt.accountType === 'CLINICIAN' ? '의료인 가입 신청을 접수했습니다' : '개인 가입 신청을 완료했습니다'}</h2><p className="enroll-description"><strong>{receipt.username}</strong> 계정이 개발 환경에 등록되었습니다.</p><dl><div><dt>가입 유형</dt><dd>{receipt.accountType === 'CLINICIAN' ? '의료인 (판독의)' : '개인'}</dd></div><div><dt>처리 상태</dt><dd>{receipt.status === 'PENDING_CREDENTIAL_REVIEW' ? '의료인 자격 확인 대기' : '개발 계정 등록 완료'}</dd></div><div><dt>휴대폰 확인</dt><dd>{receipt.phoneVerification === 'MOCK_VERIFIED' ? '테스트 인증 (실제 인증 아님)' : '번호 확인 완료'}</dd></div></dl><p className="enroll-hint">실제 본인확인·로그인·인증서 발급은 연결되지 않았습니다. 가입 정보는 개발 서버 재시작 시 초기화됩니다.</p><a className="enroll-primary" href="#/">관리 화면으로 이동 <ArrowRight size={17} /></a></div>}
        </div>
      </section>
    </main>
    <footer className="enroll-footer">© INVIZ · Vision-Certi <span>안전한 인증과 명확한 동의를 위한 시작</span></footer>
  </div>;
}
