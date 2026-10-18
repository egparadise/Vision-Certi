import { useState } from 'react';
import { CertificateEnrollmentView } from './CertificateEnrollmentView';
import './certificate-login.css';

type Help = 'financial' | 'center' | 'transfer' | null;

export function CertificateLoginView() {
  const [help, setHelp] = useState<Help>(null);
  const [jointLogin, setJointLogin] = useState(false);

  if (jointLogin) return (
    <main className="certificate-login-flow">
      <button className="certificate-login-back" onClick={() => setJointLogin(false)}>← 인증서 로그인 선택</button>
      <CertificateEnrollmentView initialMode="PC_LOGIN" proofMethod="external" />
    </main>
  );

  return (
    <main className="certificate-login-page">
      <nav className="certificate-login-admin-entry"><a href="#/admin/login">관리자 로그인</a></nav>
      <section className="certificate-login-card" aria-labelledby="certificate-login-title">
        <h1 id="certificate-login-title">의료인 공동인증서 로그인</h1>
        <div className="certificate-login-primary">
          <button type="button" onClick={() => setJointLogin(true)}>공동인증서 로그인</button>
        </div>
        <nav className="certificate-login-tools" aria-label="인증서 관리">
          <button type="button" onClick={() => setHelp('center')}>인증센터</button>
          <button type="button" onClick={() => setHelp('transfer')}>공동인증서<br />가져오기/내보내기</button>
          <a href="#/physician/register">의사 직접 가입<br />인증서 등록</a>
        </nav>
        {help && (
          <section className="certificate-login-help" role="status" aria-live="polite">
            <h2>{help === 'financial' ? '금융인증서 서비스 연결 준비' : help === 'transfer' ? '공동인증서 가져오기/내보내기' : '인증센터'}</h2>
            <p>{help === 'financial'
              ? '금융인증기관 연결이 아직 설정되지 않았습니다. 현재는 등록된 공동인증서로 로그인할 수 있습니다.'
              : help === 'transfer'
                ? '인증서 이동은 발급기관의 보안 프로그램에서 진행해주세요. 이 서비스의 가져오기/내보내기 모듈은 아직 연결되지 않았습니다. 개인키와 인증서 비밀번호는 이 웹사이트에 업로드하지 않습니다.'
                : '처음 이용하는 경우 의사 직접 가입 후 공동인증서와 의사면허·전문의 자격을 등록해주세요. 등록·활성화 후에도 매번 공동인증서로 로그인합니다. 만료·갱신된 인증서는 다시 확인해야 합니다.'}</p>
            {help === 'center' && <a href="#/certificates/register">인증서 등록·관리로 이동</a>}
            <button type="button" onClick={() => setHelp(null)}>닫기</button>
          </section>
        )}
      </section>
    </main>
  );
}
