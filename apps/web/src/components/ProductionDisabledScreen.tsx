import React from 'react';
import { ShieldAlert, Lock } from 'lucide-react';

export const ProductionDisabledScreen: React.FC = () => {
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        minHeight: '100vh',
        backgroundColor: 'var(--navy-950)',
        color: 'white',
        padding: '24px',
        textAlign: 'center',
        fontFamily: 'var(--font-sans)',
      }}
      data-testid="production-disabled-screen"
    >
      <div
        style={{
          maxWidth: '560px',
          backgroundColor: 'var(--navy-900)',
          borderRadius: '12px',
          padding: '36px',
          border: '1px solid var(--navy-800)',
          boxShadow: '0 20px 25px -5px rgba(0,0,0,0.5)',
        }}
      >
        <div style={{ color: 'var(--red-600)', marginBottom: '16px' }}>
          <ShieldAlert size={48} style={{ margin: '0 auto' }} />
        </div>

        <h1 style={{ fontSize: '1.4rem', fontWeight: 800, marginBottom: '12px' }}>
          운영 환경 데모 모드 차단 안내
        </h1>

        <p style={{ fontSize: '0.9rem', color: 'var(--navy-300)', lineHeight: '1.6', marginBottom: '20px' }}>
          보안 지침에 따라 <strong>운영 빌드(Production Build)</strong>에서는 합성 신원,
          임의 기관 전환, 개발용 HTTP 헤더(<code>X-Tenant-Id</code>, <code>X-Actor-Id</code>)
          주입이 원천 차단됩니다.
        </p>

        <div
          style={{
            backgroundColor: 'var(--navy-850)',
            padding: '16px',
            borderRadius: '8px',
            fontSize: '0.8rem',
            color: 'var(--navy-300)',
            textAlign: 'left',
            lineHeight: '1.5',
            border: '1px solid var(--navy-700)',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontWeight: 700, color: 'white', marginBottom: '8px' }}>
            <Lock size={16} style={{ color: 'var(--teal-400)' }} />
            운영 환경 연동 요구 조건:
          </div>
          <ul style={{ paddingLeft: '20px', margin: 0 }}>
            <li>병원 통합 IdP / Keycloak 기반 OIDC 로그인 세션 필요</li>
            <li>HttpOnly, Secure 세션 쿠키 및 CSRF 토큰 적용</li>
            <li>기관별 접근 제어(RLS) 및 mTLS 서비스 자격 증명 필수</li>
          </ul>
        </div>

        <div style={{ marginTop: '24px', fontSize: '0.75rem', color: 'var(--navy-500)' }}>
          * 개발 및 테스트 목적으로 실행하려면 개발 모드(<code>npm run dev</code>)로 실행하거나 환경변수 <code>VITE_ENABLE_DEMO=true</code>를 설정하십시오.
        </div>
      </div>
    </div>
  );
};
