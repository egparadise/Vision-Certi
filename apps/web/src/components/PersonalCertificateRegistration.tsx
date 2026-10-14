import { useEffect, useRef, useState } from 'react';
import './personal-certificate-registration.css';

type Operation = 'REGISTER' | 'REVOKE';
type CertificateType = 'JOINT' | 'FINANCIAL';
type CustomerType = 'PERSONAL' | 'SOLE_PROPRIETOR' | 'CORPORATION';
type Provider = '신분증' | 'ARS' | 'TOSS' | 'PASS';
const customerLabels: Record<CustomerType, string> = { PERSONAL: '개인', SOLE_PROPRIETOR: '개인사업자', CORPORATION: '법인' };

export function PersonalCertificateRegistration() {
  const [operation, setOperation] = useState<Operation>('REGISTER');
  const [certificate, setCertificate] = useState<CertificateType>('JOINT');
  const [customer, setCustomer] = useState<CustomerType>('PERSONAL');
  const [phase, setPhase] = useState<'INPUT' | 'IDENTITY'>('INPUT');
  const [showChannels, setShowChannels] = useState(false);
  const [provider, setProvider] = useState<Provider | null>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const readyForIdentity = phase === 'IDENTITY';
  const operationLabel = operation === 'REGISTER' ? '타행·타기관 인증서 등록' : '이미 등록된 타행·타기관 인증서 해제';
  const certificateLabel = certificate === 'JOINT' ? '공동인증서' : '금융인증서';
  const stepLabels = ['정보입력', '본인확인', operation === 'REGISTER' ? '인증서 등록' : '인증서 해제', '완료'];

  useEffect(() => { heading.current?.focus(); }, [phase]);
  function back() { setPhase('INPUT'); setShowChannels(false); setProvider(null); }

  return (
    <main className="personal-certificate-page">
      <a className="personal-certificate-home" href="#/login">← 인증서 로그인</a>
      <h1 ref={heading} tabIndex={-1}>타행·타기관인증서등록/해제</h1>
      <ol className="personal-certificate-steps" aria-label="인증서 등록 진행 단계">
        {stepLabels.map((label, index) => {
          const active = index === (readyForIdentity ? 1 : 0);
          return <li key={index} aria-current={active ? 'step' : undefined} className={active ? 'current' : ''}>
            <span aria-hidden="true">0{index + 1}{active && ` ${label}`}</span>
            <span className="certificate-sr-only">{index + 1}단계 {label}</span>
          </li>;
        })}
      </ol>

      <form onSubmit={event => { event.preventDefault(); setPhase('IDENTITY'); }}>
        <div className="personal-certificate-form">
          <div className="personal-certificate-row">
            <div id="operation-label" className="personal-certificate-label">등록/해제 구분</div>
            <div className="personal-certificate-value" role={readyForIdentity ? undefined : 'radiogroup'} aria-labelledby="operation-label">
              {readyForIdentity ? operationLabel : <>
                <label><input type="radio" name="operation" checked={operation === 'REGISTER'} onChange={() => setOperation('REGISTER')} />타행·타기관 인증서 등록</label>
                <label><input type="radio" name="operation" checked={operation === 'REVOKE'} onChange={() => setOperation('REVOKE')} />이미 등록된 타행·타기관 인증서 해제</label>
              </>}
            </div>
          </div>
          <div className="personal-certificate-row">
            <div id="certificate-type-label" className="personal-certificate-label">인증서 종류</div>
            <div className="personal-certificate-value" role={readyForIdentity ? undefined : 'radiogroup'} aria-labelledby="certificate-type-label">
              {readyForIdentity ? certificateLabel : <>
                <label><input type="radio" name="certificateType" checked={certificate === 'FINANCIAL'} onChange={() => setCertificate('FINANCIAL')} />금융인증서</label>
                <label><input type="radio" name="certificateType" checked={certificate === 'JOINT'} onChange={() => setCertificate('JOINT')} />공동인증서</label>
              </>}
            </div>
          </div>
          <div className="personal-certificate-row">
            <div id="customer-type-label" className="personal-certificate-label">고객 구분</div>
            <div className="personal-certificate-value" role={readyForIdentity ? undefined : 'radiogroup'} aria-labelledby="customer-type-label">
              {readyForIdentity ? customerLabels[customer] : (Object.keys(customerLabels) as CustomerType[]).map(value =>
                <label key={value}><input type="radio" name="customerType" checked={customer === value} onChange={() => setCustomer(value)} />{customerLabels[value]}</label>)}
            </div>
          </div>
        </div>

        {!readyForIdentity ? <div className="personal-certificate-actions"><button className="personal-certificate-primary" type="submit">다음</button></div> : <>
          <div className="personal-certificate-instruction">
            안전한 {operation === 'REGISTER' ? '등록' : '해제'}을 위해 본인확인 인증절차를 진행해주시기 바랍니다.
            <span>인증 수단을 선택해주세요.</span>
          </div>
          <div className="personal-certificate-actions">
            <button className="personal-certificate-primary" type="button" onClick={() => { setProvider('신분증'); setShowChannels(false); }}>신분증 인증</button>
            <button className="personal-certificate-primary" type="button" onClick={() => { setShowChannels(true); setProvider(null); }}>2채널(ARS) / 토스(TOSS),패스(PASS) 인증</button>
          </div>
          {showChannels && <fieldset className="personal-certificate-channels"><legend>본인확인 수단 선택</legend>
            {(['ARS', 'TOSS', 'PASS'] as Provider[]).map(channel => <button type="button" key={channel} onClick={() => setProvider(channel)}>{channel} 인증</button>)}
          </fieldset>}
          {provider && <div className="personal-certificate-provider-notice" role="status">
            <strong>{provider} 본인확인 서비스 연결이 필요합니다.</strong>
            <p>현재 해당 인증기관이 연결되어 있지 않아 인증을 시작할 수 없습니다. 본인확인 결과가 검증된 뒤에만 인증서 {operation === 'REGISTER' ? '등록' : '해제'} 단계로 이동할 수 있습니다.</p>
          </div>}
          <div className="personal-certificate-actions"><button className="personal-certificate-secondary" type="button" onClick={back}>이전 · 입력정보 수정</button></div>
        </>}
      </form>
    </main>
  );
}
