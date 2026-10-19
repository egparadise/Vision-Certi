import { PhysicianAccountView } from './components/PhysicianAccountView';
import React from 'react';
import { useApp } from './context/AppContext';
import { isDemoEnabled } from './utils/env';
import { ProductionDisabledScreen } from './components/ProductionDisabledScreen';
import { Header } from './components/Header';
import { Navigation } from './components/Navigation';
import { DashboardView } from './components/DashboardView';
import { PhysicianReviewView } from './components/PhysicianReviewView';
import { DocumentListView } from './components/DocumentListView';
import { SignatureVerificationView } from './components/SignatureVerificationView';
import { DeliveryQueueView } from './components/DeliveryQueueView';
import { AuditEventsView } from './components/AuditEventsView';
import { CertificatesView } from './components/CertificatesView';
import { RegistrationView } from './components/RegistrationView';
import { DoctorAdminView } from './components/DoctorAdminView';
import { CertificateEnrollmentView } from './components/CertificateEnrollmentView';
import { CertificateLoginView } from './components/CertificateLoginView';
import { PersonalCertificateRegistration } from './components/PersonalCertificateRegistration';
import { PacsCertificateEnrollment } from './components/PacsCertificateEnrollment';
import { PacsCertificateLogin } from './components/PacsCertificateLogin';
import { AdminConsole, adminSectionFromHash } from './components/admin/AdminConsole';
import { LocalAdminLogin } from './components/admin/LocalAdminLogin';

export const AppContent: React.FC = () => {
  const { activeTab, tenantId, actorId, isMockMode, runtimeCapabilities } = useApp();

  // Production Gate: In non-dev/production builds without explicit demo flag, disable demo UI.
  // Clinical features (enrollment, review) operate on dedicated routes or when live runtime capabilities are present.
  if (!isDemoEnabled() && (!runtimeCapabilities || isMockMode)) {
    return <ProductionDisabledScreen />;
  }

  return (
    <div className="app-container">
      <Header />
      <Navigation />
      {/* Remount content by tenant+actor+mode to prevent stale cross-talk on context switches */}
      <main
        className="main-content"
        id="main-content"
        key={`${tenantId}-${actorId}-${isMockMode ? 'mock' : 'live'}`}
      >
        {activeTab === 'dashboard' && <DashboardView />}
        {activeTab === 'review' && <PhysicianReviewView />}
        {activeTab === 'documents' && <DocumentListView />}
        {activeTab === 'signatures' && <SignatureVerificationView />}
        {activeTab === 'deliveries' && <DeliveryQueueView />}
        {activeTab === 'audit' && <AuditEventsView />}
        {activeTab === 'certificates' && <CertificatesView />}
      </main>

      <footer
        style={{
          borderTop: '1px solid var(--navy-200)',
          backgroundColor: 'white',
          padding: '16px 24px',
          fontSize: '0.75rem',
          color: 'var(--navy-500)',
          textAlign: 'center',
        }}
      >
        <div>
          <strong>Vision-Certi</strong> 의료 전자의무기록 원격판독 전자서명 플랫폼 — 인비즈 솔루션
        </div>
        {isMockMode ? (
          <div style={{ marginTop: '4px', color: 'var(--amber-700)', fontWeight: 600 }}>
            ⚠️ 경고: 본 플랫폼은 개발 및 검증용 모의 환경(DEV MOCK)이며 실제 의료 환경 및 환자 진료에 사용할 수 없습니다.
          </div>
        ) : (
          <div style={{ marginTop: '4px', color: '#0369a1', fontWeight: 500 }}>
            API 연결 상태 (프로파일: {runtimeCapabilities?.profile || '확인 중'}, 영속성: {runtimeCapabilities?.persistence?.mode || '확인 중'})
          </div>
        )}
      </footer>
    </div>
  );
};

/** Admin mode (관리자 모드): same production gate as the main console; the console itself requires role ADMIN. */
const AdminRoute: React.FC<{ section: ReturnType<typeof adminSectionFromHash> }> = ({ section }) => {
  const { isMockMode, runtimeCapabilities } = useApp();
  if (!isDemoEnabled() && (!runtimeCapabilities || isMockMode)) {
    return <ProductionDisabledScreen />;
  }
  return (
    <div className="app-container">
      <Header />
      <Navigation />
      <main className="main-content" id="main-content">
        <AdminConsole section={section} />
      </main>
    </div>
  );
};

export const App: React.FC = () => {
  const [hash, setHash] = React.useState(window.location.hash);
  const { runtimeCapabilities } = useApp();

  React.useEffect(() => {
    const change = () => setHash(window.location.hash);
    window.addEventListener('hashchange', change);
    return () => window.removeEventListener('hashchange', change);
  }, []);

  // Handle OIDC authorization code callback from popup window
  React.useEffect(() => {
    const searchParams = new URLSearchParams(window.location.search);
    const code = searchParams.get('code');
    const state = searchParams.get('state');
    if (code && state && window.opener && !window.opener.closed) {
      window.opener.postMessage(
        { type: 'OIDC_CODE_CALLBACK', code, state },
        window.location.origin
      );
      window.close();
    }
  }, []);

  if (hash === '#/physician/register' || hash === '#/physician/account') return <PhysicianAccountView />;
  if (hash === '#/physician/login') return <main className="personal-certificate-page"><a href="#/physician/register">의사 가입·가입 이어하기</a><CertificateEnrollmentView initialMode="PC_LOGIN" proofMethod="external" /></main>;

  if (hash === '#/login' || hash === '#/certificates/login') return <CertificateLoginView />;
  if (hash === '#/admin/login') return <LocalAdminLogin />;
  if (hash === '#/certificates/register') return <PersonalCertificateRegistration />;
  if (hash.startsWith('#/certificates/pacs-enroll') || hash.startsWith('#/certificates/enroll')) return <main className="personal-certificate-page"><PacsCertificateEnrollment /></main>;

  if (hash.startsWith('#/signup')) {
    const allowSignup = isDemoEnabled() || Boolean(runtimeCapabilities?.personalSignup?.available);
    return allowSignup ? <RegistrationView /> : <ProductionDisabledScreen />;
  }

  if (hash === '#/admin' || hash.startsWith('#/admin/')) {
    return <AdminRoute section={adminSectionFromHash(hash)} />;
  }

  if (hash.startsWith('#/doctors')) {
    return (
      <div className="app-container">
        <Header />
        <Navigation />
        <main className="main-content" id="main-content">
          <DoctorAdminView />
        </main>
      </div>
    );
  }

  if (hash.startsWith('#/activate')) {
    return (
      <div className="app-container">
        <Header />
        <Navigation />
        <main className="main-content" id="main-content">
          <PacsCertificateEnrollment />
        </main>
      </div>
    );
  }

  if (hash.startsWith('#/certificates/pacs-login')) {
    return <main className="personal-certificate-page"><PacsCertificateLogin /></main>;
  }

  if (hash.startsWith('#/doctor/sign-requests')) {
    return (
      <div className="app-container">
        <Header />
        <Navigation />
        <main className="main-content" id="main-content">
          <CertificateEnrollmentView initialMode="PC_LOGIN" proofMethod="external" />
        </main>
      </div>
    );
  }

  return <AppContent />;
};

export default App;
