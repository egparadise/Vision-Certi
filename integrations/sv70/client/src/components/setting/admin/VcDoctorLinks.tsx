import React, { useState, useEffect, useCallback } from 'react';
import { VisionCertiService } from '@services/api/VisionCertiService';
import { VcDoctorLink, CreateDoctorLinkRequest } from '@shared/types/visionCerti.types';
import { useUserStore } from '@stores/useUserStore';
import { Toast } from '@libs/Toast';

export default function VcDoctorLinks() {
  const userData = useUserStore((state) => state.userData);
  const isAdmin = userData?.group_idx === 1 || (userData?.group_level ?? 0) >= 98;

  const [links, setLinks] = useState<VcDoctorLink[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Form state
  const [userIdx, setUserIdx] = useState<string>('');
  const [vcTenantId, setVcTenantId] = useState<string>('hospital-a');
  const [doctorId, setDoctorId] = useState<string>('');
  const [evidenceRef, setEvidenceRef] = useState<string>('');
  const [note, setNote] = useState<string>('');
  const [evidenceConfirmed, setEvidenceConfirmed] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  // Revoke state
  const [revokingId, setRevokingId] = useState<number | null>(null);
  const [revokeReason, setRevokeReason] = useState<string>('');

  const fetchLinks = useCallback(async () => {
    if (!isAdmin) return;
    setLoading(true);
    setError(null);
    try {
      const res = await VisionCertiService.listDoctorLinks();
      setLinks(res.items || []);
    } catch (err: any) {
      setError(err?.message || '의사 계정 연동 목록을 불러오지 못했습니다.');
    } finally {
      setLoading(false);
    }
  }, [isAdmin]);

  useEffect(() => {
    fetchLinks();
  }, [fetchLinks]);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!evidenceConfirmed) {
      Toast('증빙 확인 체크박스에 동의해야 등록할 수 있습니다.', 'warning');
      return;
    }
    const parsedUserIdx = parseInt(userIdx, 10);
    if (isNaN(parsedUserIdx) || parsedUserIdx <= 0) {
      Toast('올바른 PACS 사용자 번호(user_idx)를 입력해주세요.', 'warning');
      return;
    }
    if (!doctorId.trim() || !evidenceRef.trim()) {
      Toast('모든 필수 항목을 입력해주세요.', 'warning');
      return;
    }

    setSubmitting(true);
    try {
      const payload: CreateDoctorLinkRequest = {
        user_idx: parsedUserIdx,
        vc_tenant_id: vcTenantId.trim() || 'hospital-a',
        vc_doctor_id: doctorId.trim(),
        evidence_ref: evidenceRef.trim(),
        note: note.trim() || undefined,
      };
      await VisionCertiService.createDoctorLink(payload);
      Toast('의사 계정 연동이 성공적으로 등록되었습니다.', 'success');
      setUserIdx('');
      setDoctorId('');
      setEvidenceRef('');
      setNote('');
      setEvidenceConfirmed(false);
      await fetchLinks();
    } catch (err: any) {
      Toast(err?.message || '연동 등록에 실패했습니다.', 'error');
    } finally {
      setSubmitting(false);
    }
  };

  const handleRevoke = async (linkIdx: number) => {
    if (!revokeReason.trim()) {
      Toast('연동 해제 사유를 입력해주세요.', 'warning');
      return;
    }
    try {
      await VisionCertiService.revokeDoctorLink(linkIdx, revokeReason.trim());
      Toast('의사 계정 연동이 해제되었습니다.', 'success');
      setRevokingId(null);
      setRevokeReason('');
      await fetchLinks();
    } catch (err: any) {
      Toast(err?.message || '연동 해제 처리에 실패했습니다.', 'error');
    }
  };

  if (!isAdmin) {
    return (
      <div style={{ padding: '24px', color: '#f87171' }} data-testid="vc-doctor-links-403">
        <h3>권한 오류: 관리자 권한 필요 (403 ADMIN_ROLE_REQUIRED)</h3>
        <p>이 기능은 시스템 관리자(group_idx=1 또는 group_level&gt;=98)만 접근할 수 있습니다.</p>
      </div>
    );
  }

  return (
    <div style={{ padding: '24px', color: '#e2e8f0', maxWidth: '1000px' }} data-testid="vc-doctor-links-admin">
      <div style={{ marginBottom: '24px' }}>
        <h2 style={{ fontSize: '20px', fontWeight: 600, color: '#f8fafc', marginBottom: '8px' }}>
          🛡️ Vision-Certi 의사 계정 연동 관리
        </h2>
        <p style={{ color: '#94a3b8', fontSize: '13px' }}>
          PACS 판독의사와 Vision-Certi 공인전자서명 의사 계정 간의 1:1 매핑을 관리합니다.
          보안 정책상 이메일 자동 바인딩은 금지되며, 관리자의 공식 증빙 번호(Evidence Ref)가 필수입니다.
        </p>
      </div>

      {/* 등록 폼 */}
      <div
        style={{
          background: '#1e293b',
          border: '1px solid #334155',
          borderRadius: '8px',
          padding: '20px',
          marginBottom: '28px',
        }}
      >
        <h3 style={{ fontSize: '15px', fontWeight: 600, color: '#38bdf8', marginBottom: '16px' }}>
          새 의사 계정 연동 등록
        </h3>
        <form onSubmit={handleCreate}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: '16px', marginBottom: '16px' }}>
            <div>
              <label style={{ display: 'block', fontSize: '12px', color: '#94a3b8', marginBottom: '4px' }}>
                PACS 사용자 번호 (user_idx) *
              </label>
              <input
                type="number"
                value={userIdx}
                onChange={(e) => setUserIdx(e.target.value)}
                placeholder="예: 101"
                required
                style={{
                  width: '100%',
                  padding: '8px 12px',
                  background: '#0f172a',
                  border: '1px solid #475569',
                  borderRadius: '4px',
                  color: '#fff',
                }}
              />
            </div>
            <div>
              <label style={{ display: 'block', fontSize: '12px', color: '#94a3b8', marginBottom: '4px' }}>
                Vision-Certi 의사 식별자 (Doctor ID / UUID) *
              </label>
              <input
                type="text"
                value={doctorId}
                onChange={(e) => setDoctorId(e.target.value)}
                placeholder="예: doc-uuid-1"
                required
                style={{
                  width: '100%',
                  padding: '8px 12px',
                  background: '#0f172a',
                  border: '1px solid #475569',
                  borderRadius: '4px',
                  color: '#fff',
                }}
              />
            </div>
            <div>
              <label style={{ display: 'block', fontSize: '12px', color: '#94a3b8', marginBottom: '4px' }}>
                Vision-Certi 테넌트 ID (Tenant ID) *
              </label>
              <input
                type="text"
                value={vcTenantId}
                onChange={(e) => setVcTenantId(e.target.value)}
                placeholder="예: hospital-a"
                required
                style={{
                  width: '100%',
                  padding: '8px 12px',
                  background: '#0f172a',
                  border: '1px solid #475569',
                  borderRadius: '4px',
                  color: '#fff',
                }}
              />
            </div>
            <div>
              <label style={{ display: 'block', fontSize: '12px', color: '#94a3b8', marginBottom: '4px' }}>
                대면/인증 확인 증빙 번호 (Evidence Ref) *
              </label>
              <input
                type="text"
                value={evidenceRef}
                onChange={(e) => setEvidenceRef(e.target.value)}
                placeholder="예: HR-2026-MED-042"
                required
                style={{
                  width: '100%',
                  padding: '8px 12px',
                  background: '#0f172a',
                  border: '1px solid #475569',
                  borderRadius: '4px',
                  color: '#fff',
                }}
              />
            </div>
            <div style={{ gridColumn: 'span 2' }}>
              <label style={{ display: 'block', fontSize: '12px', color: '#94a3b8', marginBottom: '4px' }}>
                비고 및 사유 (Note, 선택)
              </label>
              <input
                type="text"
                value={note}
                onChange={(e) => setNote(e.target.value)}
                placeholder="연동 등록 참고사항"
                style={{
                  width: '100%',
                  padding: '8px 12px',
                  background: '#0f172a',
                  border: '1px solid #475569',
                  borderRadius: '4px',
                  color: '#fff',
                }}
              />
            </div>
          </div>

          <div style={{ marginBottom: '16px', display: 'flex', alignItems: 'center', gap: '8px' }}>
            <input
              type="checkbox"
              id="evidence_checkbox"
              checked={evidenceConfirmed}
              onChange={(e) => setEvidenceConfirmed(e.target.checked)}
              style={{ cursor: 'pointer' }}
            />
            <label htmlFor="evidence_checkbox" style={{ fontSize: '12px', color: '#cbd5e1', cursor: 'pointer' }}>
              본 관리자는 해당 의사의 의사면허증 원본 및 신원 확인 서류를 검토하였음을 서약합니다 (필수).
            </label>
          </div>

          <button
            type="submit"
            disabled={!evidenceConfirmed || submitting}
            style={{
              padding: '8px 20px',
              background: evidenceConfirmed ? '#0284c7' : '#334155',
              border: 'none',
              borderRadius: '4px',
              color: '#fff',
              cursor: evidenceConfirmed ? 'pointer' : 'not-allowed',
              fontWeight: 600,
              fontSize: '13px',
            }}
          >
            {submitting ? '등록 중...' : '연동 등록'}
          </button>
        </form>
      </div>

      {/* 목록 테이블 */}
      <div
        style={{
          background: '#1e293b',
          border: '1px solid #334155',
          borderRadius: '8px',
          padding: '20px',
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
          <h3 style={{ fontSize: '15px', fontWeight: 600, color: '#f8fafc' }}>
            연동된 의사 목록 ({links.length}건)
          </h3>
          <button
            onClick={fetchLinks}
            style={{
              padding: '4px 12px',
              background: '#334155',
              border: 'none',
              borderRadius: '4px',
              color: '#cbd5e1',
              cursor: 'pointer',
              fontSize: '12px',
            }}
          >
            새로고침
          </button>
        </div>

        {error && <div style={{ color: '#f87171', marginBottom: '12px', fontSize: '13px' }}>{error}</div>}

        {loading ? (
          <div style={{ padding: '20px', textAlign: 'center', color: '#94a3b8' }}>불러오는 중...</div>
        ) : links.length === 0 ? (
          <div style={{ padding: '24px', textAlign: 'center', color: '#64748b' }}>
            등록된 의사 연동 정보가 없습니다.
          </div>
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '13px' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid #334155', color: '#94a3b8', textAlign: 'left' }}>
                <th style={{ padding: '8px' }}>링크 ID</th>
                <th style={{ padding: '8px' }}>PACS User</th>
                <th style={{ padding: '8px' }}>VC 테넌트</th>
                <th style={{ padding: '8px' }}>VC 의사 ID</th>
                <th style={{ padding: '8px' }}>증빙 번호</th>
                <th style={{ padding: '8px' }}>상태</th>
                <th style={{ padding: '8px' }}>등록일시</th>
                <th style={{ padding: '8px', textAlign: 'right' }}>관리</th>
              </tr>
            </thead>
            <tbody>
              {links.map((link) => (
                <tr key={link.linkIdx} style={{ borderBottom: '1px solid #1e293b' }}>
                  <td style={{ padding: '8px', fontFamily: 'monospace' }}>#{link.linkIdx}</td>
                  <td style={{ padding: '8px' }}>
                    {link.userId ? `${link.userName || ''} (${link.userId})` : `Idx: ${link.userIdx}`}
                  </td>
                  <td style={{ padding: '8px', color: '#94a3b8' }}>{link.vcTenantId}</td>
                  <td style={{ padding: '8px', fontFamily: 'monospace' }}>{link.vcDoctorId}</td>
                  <td style={{ padding: '8px', fontFamily: 'monospace', color: '#38bdf8' }}>{link.evidenceRef}</td>
                  <td style={{ padding: '8px' }}>
                    <span
                      style={{
                        padding: '2px 8px',
                        borderRadius: '4px',
                        fontSize: '11px',
                        fontWeight: 600,
                        backgroundColor: link.status === 'ACTIVE' ? '#14532d' : '#7f1d1d',
                        color: link.status === 'ACTIVE' ? '#86efac' : '#fca5a5',
                      }}
                    >
                      {link.status}
                    </span>
                  </td>
                  <td style={{ padding: '8px', color: '#94a3b8', fontSize: '12px' }}>
                    {link.createdAt ? new Date(link.createdAt).toLocaleString() : '-'}
                  </td>
                  <td style={{ padding: '8px', textAlign: 'right' }}>
                    {link.status === 'ACTIVE' && (
                      <button
                        onClick={() => {
                          setRevokingId(link.linkIdx);
                          setRevokeReason('');
                        }}
                        style={{
                          padding: '4px 10px',
                          background: '#7f1d1d',
                          border: 'none',
                          borderRadius: '4px',
                          color: '#fecaca',
                          cursor: 'pointer',
                          fontSize: '11px',
                        }}
                      >
                        연동 해제
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* 해제 사유 입력 모달 */}
      {revokingId !== null && (
        <div
          style={{
            position: 'fixed',
            inset: 0,
            backgroundColor: 'rgba(0,0,0,0.6)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 9999,
          }}
        >
          <div
            style={{
              background: '#1e293b',
              border: '1px solid #475569',
              borderRadius: '8px',
              padding: '24px',
              width: '450px',
              color: '#fff',
            }}
          >
            <h4 style={{ fontSize: '16px', fontWeight: 600, marginBottom: '12px' }}>
              의사 계정 연동 해제 (Link #{revokingId})
            </h4>
            <p style={{ fontSize: '13px', color: '#94a3b8', marginBottom: '16px' }}>
              연동을 해제하면 해당 의사는 더 이상 이 PACS 계정으로 전자서명을 진행할 수 없습니다. 해제 사유를 입력해주세요.
            </p>
            <input
              type="text"
              value={revokeReason}
              onChange={(e) => setRevokeReason(e.target.value)}
              placeholder="해제 사유 (예: 퇴사, 부서 이동, 면허 변경 등)"
              style={{
                width: '100%',
                padding: '8px 12px',
                background: '#0f172a',
                border: '1px solid #475569',
                borderRadius: '4px',
                color: '#fff',
                marginBottom: '16px',
              }}
            />
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px' }}>
              <button
                type="button"
                onClick={() => setRevokingId(null)}
                style={{
                  padding: '6px 14px',
                  background: '#334155',
                  border: 'none',
                  borderRadius: '4px',
                  color: '#cbd5e1',
                  cursor: 'pointer',
                }}
              >
                취소
              </button>
              <button
                type="button"
                onClick={() => handleRevoke(revokingId)}
                style={{
                  padding: '6px 14px',
                  background: '#dc2626',
                  border: 'none',
                  borderRadius: '4px',
                  color: '#fff',
                  cursor: 'pointer',
                  fontWeight: 600,
                }}
              >
                해제 확인
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
