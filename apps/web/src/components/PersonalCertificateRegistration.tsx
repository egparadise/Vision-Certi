import { useEffect, useRef, useState } from 'react';
import { PacsCertificateEnrollment } from './PacsCertificateEnrollment';
import './personal-certificate-registration.css';

type Operation = 'REGISTER' | 'REVOKE';
type CertificateType = 'JOINT' | 'FINANCIAL';
type CustomerType = 'PERSONAL' | 'SOLE_PROPRIETOR' | 'CORPORATION';
const customerLabels: Record<CustomerType, string> = { PERSONAL: '개인', SOLE_PROPRIETOR: '개인사업자', CORPORATION: '법인' };

export function PersonalCertificateRegistration() {
  const [operation, setOperation] = useState<Operation>('REGISTER');
  const [certificate, setCertificate] = useState<CertificateType>('JOINT');
  const [customer, setCustomer] = useState<CustomerType>('PERSONAL');
  const [phase, setPhase] = useState<'INPUT' | 'CERTIFICATE'>('INPUT');
  const heading = useRef<HTMLHeadingElement>(null);
  const readyForCertificate = phase === 'CERTIFICATE';
  const operationLabel = operation === 'REGISTER' ? '타행·타기관 인증서 등록' : '이미 등록된 타행·타기관 인증서 해제';
  const certificateLabel = certificate === 'JOINT' ? '공동인증서' : '금융인증서';
  const stepLabels = ['정보입력', operation === 'REGISTER' ? '인증서 등록' : '인증서 해제', '완료'];

  useEffect(() => { heading.current?.focus(); }, [phase]);
  function back() { setPhase('INPUT'); }

  return (
    <main className="personal-certificate-page">
      <a className="personal-certificate-home" href="#/login">← 인증서 로그인</a>
      <h1 ref={heading} tabIndex={-1}>타행·타기관인증서등록/해제</h1>
      <ol className="personal-certificate-steps" aria-label="인증서 등록 진행 단계">
        {stepLabels.map((label, index) => {
          const active = index === (readyForCertificate ? 1 : 0);
          return <li key={index} aria-current={active ? 'step' : undefined} className={active ? 'current' : ''}>
            <span aria-hidden="true">0{index + 1}{active && ` ${label}`}</span>
            <span className="certificate-sr-only">{index + 1}단계 {label}</span>
          </li>;
        })}
      </ol>

      <form onSubmit={event => { event.preventDefault(); setPhase('CERTIFICATE'); }}>
        <div className="personal-certificate-form">
          <div className="personal-certificate-row">
            <div id="operation-label" className="personal-certificate-label">등록/해제 구분</div>
            <div className="personal-certificate-value" role={readyForCertificate ? undefined : 'radiogroup'} aria-labelledby="operation-label">
              {readyForCertificate ? operationLabel : <>
                <label><input type="radio" name="operation" checked={operation === 'REGISTER'} onChange={() => setOperation('REGISTER')} />타행·타기관 인증서 등록</label>
                <label><input type="radio" name="operation" checked={operation === 'REVOKE'} onChange={() => setOperation('REVOKE')} />이미 등록된 타행·타기관 인증서 해제</label>
              </>}
            </div>
          </div>
          <div className="personal-certificate-row">
            <div id="certificate-type-label" className="personal-certificate-label">인증서 종류</div>
            <div className="personal-certificate-value" role={readyForCertificate ? undefined : 'radiogroup'} aria-labelledby="certificate-type-label">
              {readyForCertificate ? certificateLabel : <>
                <label><input type="radio" name="certificateType" checked={certificate === 'FINANCIAL'} onChange={() => setCertificate('FINANCIAL')} />금융인증서</label>
                <label><input type="radio" name="certificateType" checked={certificate === 'JOINT'} onChange={() => setCertificate('JOINT')} />공동인증서</label>
              </>}
            </div>
          </div>
          <div className="personal-certificate-row">
            <div id="customer-type-label" className="personal-certificate-label">고객 구분</div>
            <div className="personal-certificate-value" role={readyForCertificate ? undefined : 'radiogroup'} aria-labelledby="customer-type-label">
              {readyForCertificate ? customerLabels[customer] : (Object.keys(customerLabels) as CustomerType[]).map(value =>
                <label key={value}><input type="radio" name="customerType" checked={customer === value} onChange={() => setCustomer(value)} />{customerLabels[value]}</label>)}
            </div>
          </div>
        </div>

        {!readyForCertificate && <div className="personal-certificate-actions"><button className="personal-certificate-primary" type="submit">다음</button></div>}
      </form>
      {readyForCertificate && <>
        <div className="personal-certificate-actions"><button className="personal-certificate-secondary" type="button" onClick={back}>이전 · 입력정보 수정</button></div>
        {operation === 'REGISTER' && certificate === 'JOINT' && customer === 'PERSONAL'
          ? <PacsCertificateEnrollment />
          : <div className="personal-certificate-provider-notice" role="status">
              <strong>{operation === 'REVOKE' ? '인증서 해제 안내' : '인증서 등록 안내'}</strong>
              <p>{operation === 'REVOKE'
                ? '인증서 해제는 관리자에게 요청해주세요. 이 화면에서 등록을 해제하는 기능은 아직 지원하지 않습니다.'
                : certificate === 'FINANCIAL'
                  ? '금융인증서 등록 서비스가 아직 연결되지 않았습니다.'
                  : '현재는 PACS 개인 의사 계정의 공동인증서 등록을 지원합니다. 개인사업자·법인 인증서 등록은 아직 지원하지 않습니다.'}</p>
            </div>}
      </>}
    </main>
  );
}
