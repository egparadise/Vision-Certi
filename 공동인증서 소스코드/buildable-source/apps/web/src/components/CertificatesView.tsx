import React from 'react';
import { Award, Lock, KeyRound, CheckCircle2, AlertTriangle } from 'lucide-react';
import { useApp } from '../context/AppContext';
import { ExternalCaStatus } from '../types/certificate';
import { InfoPopover } from './InfoPopover';

export const CertificatesView: React.FC = () => {
  const { tenantId, mockStore } = useApp();
  const doctors = mockStore.doctors[tenantId] || [];

  const renderCaBadge = (status?: ExternalCaStatus) => {
    switch (status) {
      case 'GOOD':
        return <span className="badge badge-completed" style={{ fontSize: '0.7rem' }}>CA 정상 (GOOD)</span>;
      case 'REVOKED':
        return <span className="badge badge-failed" style={{ fontSize: '0.7rem' }}>CA 폐기 (REVOKED)</span>;
      case 'UNKNOWN':
        return <span className="badge badge-pending" style={{ fontSize: '0.7rem' }}>CA 미확인 (UNKNOWN)</span>;
      case 'STALE':
        return <span className="badge" style={{ backgroundColor: '#fef08a', color: '#854d0e', fontSize: '0.7rem' }}>CRL 지연 (STALE)</span>;
      default:
        return <span className="badge">{status || '미확인'}</span>;
    }
  };

  return (
    <div style={{ maxWidth: '1200px', margin: '0 auto' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px', flexWrap: 'wrap', gap: '12px' }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <h2 style={{ fontSize: '1.4rem', fontWeight: 800, color: 'var(--navy-900)' }}>
              인증서·서명키 현황
            </h2>
            <InfoPopover
              title="인증서와 판독문 서명키"
              buttonText="아키텍처 안내"
              description="의사 신원 확인용 외부 인증서와 판독문 전자서명용 서버 키는 목적과 관리 주체가 다릅니다."
              bullets={[
                "외부 인증서는 의사 PC에서 일회용 로그인 요청에 서명하며, 개인키·비밀번호는 서버로 전송하지 않습니다.",
                "판독문 서명키는 서버에 연결된 OpenBao Transit에서 관리합니다.",
                "임상 사용 적합성은 별도의 서버 운영 상태와 기관 검토 결과로 확인해야 합니다."
              ]}
              badge="구조 안내"
            />
          </div>
        </div>
        <div style={{ display: 'flex', gap: '8px' }}>
          <a
            href="#/certificates/enroll"
            className="btn btn-primary"
            style={{ fontSize: '0.8rem', textDecoration: 'none', display: 'inline-flex', alignItems: 'center', gap: '4px' }}
          >
            <KeyRound size={14} /> 의사 인증서 등록 포털 &rarr;
          </a>
          <a
            href="#/doctors"
            className="btn btn-secondary"
            style={{ fontSize: '0.8rem', textDecoration: 'none', display: 'inline-flex', alignItems: 'center', gap: '4px' }}
          >
            <Award size={14} /> 관리자 인증서 대조·승인
          </a>
        </div>
      </div>

      {/* Grid: Registered External Auth Certificates & OpenBao Transit Keys */}
      <div className="detail-grid" style={{ marginBottom: '24px' }}>
        {/* Left: Registered External Doctor Certificates */}
        <div className="card">
          <div className="card-header">
            <span className="card-title">
              <Award size={18} style={{ color: 'var(--teal-700)' }} />
              소속 의사 외부 신원 인증서 등록 현황 (External Auth Certificates)
            </span>
            <span className="badge badge-completed">
              기관: {tenantId}
            </span>
          </div>
          <div className="card-body">
            {doctors.length === 0 ? (
              <div style={{ textAlign: 'center', padding: '24px', color: 'var(--navy-400)' }}>
                등록된 의사가 없습니다.
              </div>
            ) : (
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.8rem' }}>
                  <thead>
                    <tr style={{ borderBottom: '2px solid var(--navy-200)', backgroundColor: 'var(--navy-50)' }}>
                      <th style={{ padding: '8px 10px', textAlign: 'left' }}>의사 성명 / 면허</th>
                      <th style={{ padding: '8px 10px', textAlign: 'left' }}>인증서 구분</th>
                      <th style={{ padding: '8px 10px', textAlign: 'left' }}>CA 폐기 상태</th>
                      <th style={{ padding: '8px 10px', textAlign: 'left' }}>등록 상태</th>
                      <th style={{ padding: '8px 10px', textAlign: 'left' }}>관리 증빙</th>
                    </tr>
                  </thead>
                  <tbody>
                    {doctors.map((doc) => {
                      const authCert = doc.authCertificate;
                      const hasCandidates = doc.candidateCertificates && doc.candidateCertificates.length > 0;
                      return (
                        <tr key={doc.id} style={{ borderBottom: '1px solid var(--navy-100)' }}>
                          <td style={{ padding: '8px 10px' }}>
                            <strong>{doc.name}</strong>
                            <div style={{ fontSize: '0.7rem', color: 'var(--navy-500)' }}>{doc.licenseNumber}</div>
                          </td>
                          <td style={{ padding: '8px 10px' }}>
                            {authCert ? (
                              <div>
                                <div><code>{authCert.algorithm}</code></div>
                                <div style={{ fontSize: '0.7rem', color: 'var(--navy-600)', wordBreak: 'break-all' }}>
                                  SN: {authCert.serialNumber}
                                </div>
                              </div>
                            ) : hasCandidates ? (
                              <span style={{ color: '#d97706' }}>후보 심사 대기</span>
                            ) : (
                              <span style={{ color: 'var(--navy-400)' }}>(미등록)</span>
                            )}
                          </td>
                          <td style={{ padding: '8px 10px' }}>
                            {authCert ? renderCaBadge(authCert.externalCaStatus) : hasCandidates ? renderCaBadge(doc.candidateCertificates![0].externalCaStatus) : '-'}
                          </td>
                          <td style={{ padding: '8px 10px' }}>
                            {authCert ? (
                              <span className="badge badge-completed" style={{ fontSize: '0.65rem' }}>{authCert.registrationStatus}</span>
                            ) : hasCandidates ? (
                              <a href="#/doctors" style={{ fontSize: '0.7rem', color: 'var(--teal-700)' }}>대조 심사 필요 &rarr;</a>
                            ) : (
                              <span className="badge">{doc.status}</span>
                            )}
                          </td>
                          <td style={{ padding: '8px 10px', fontSize: '0.7rem' }}>
                            {authCert?.evidenceRef || '-'}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>

        {/* Right: OpenBao Transit Engine & Private CA */}
        <div className="card">
          <div className="card-header">
            <span className="card-title">
              <Lock size={18} style={{ color: 'var(--navy-700)' }} />
              OpenBao Transit 서명 엔진 상태 (Report Signing Vault)
            </span>
          </div>
          <div className="card-body">
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: '10px', marginBottom: '14px' }}>
              <div style={{ padding: '10px', backgroundColor: 'var(--navy-50)', borderRadius: '6px' }}>
                <div style={{ fontSize: '0.75rem', color: 'var(--navy-600)' }}>서명 알고리즘</div>
                <div style={{ fontSize: '1rem', fontWeight: 700, color: 'var(--navy-900)' }}>ECDSA P-256</div>
              </div>
              <div style={{ padding: '10px', backgroundColor: 'var(--navy-50)', borderRadius: '6px' }}>
                <div style={{ fontSize: '0.75rem', color: 'var(--navy-600)' }}>키 보관 백엔드</div>
                <div style={{ fontSize: '1rem', fontWeight: 700, color: 'var(--teal-700)' }}>OpenBao Transit</div>
              </div>
            </div>

            <div style={{ fontSize: '0.8rem', color: 'var(--navy-700)', lineHeight: '1.5' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '6px', marginBottom: '6px' }}>
                <CheckCircle2 size={16} style={{ color: '#16a34a' }} />
                <strong>사전등록 시 키 미발급 (Zero Key Preregistration) 원칙</strong>
              </div>
              <p style={{ margin: '0 0 10px 22px', fontSize: '0.75rem', color: 'var(--navy-600)' }}>
                관리자가 의사를 사전등록하는 시점에는 어떠한 서명키도 생성되지 않으며, 의사 본인의 자격 인증 및 동의 후에만 키가 프로비저닝됩니다.
              </p>

              <div style={{ display: 'flex', alignItems: 'center', gap: '6px', marginBottom: '6px' }}>
                <CheckCircle2 size={16} style={{ color: '#16a34a' }} />
                <strong>실시간 폐기 목록(CRL) Fail-Closed 검증</strong>
              </div>
              <p style={{ margin: '0 0 10px 22px', fontSize: '0.75rem', color: 'var(--navy-600)' }}>
                외부 CA 상태가 REVOKED, UNKNOWN, STALE일 경우 서명키 발급 및 전자서명 승인이 전면 차단됩니다.
              </p>

              <div style={{ display: 'flex', alignItems: 'center', gap: '6px', marginBottom: '6px' }}>
                <AlertTriangle size={16} style={{ color: '#d97706' }} />
                <strong>소프트웨어 Transit 기반 키 관리 (No HSM / No TSA)</strong>
              </div>
              <p style={{ margin: '0 0 0 22px', fontSize: '0.75rem', color: 'var(--navy-600)' }}>
                현재 시스템은 소프트웨어 기반 OpenBao Transit 키 관리 엔진을 사용하며, 물리적 HSM 및 타임스탬프 공인인증기관(TSA)은 연동되어 있지 않습니다. Transit의 Auto-unseal은 볼트 시작 편의 기능일 뿐 키 보호 수단이 아님을 명시합니다.
              </p>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
