import React, { useCallback, useEffect, useState } from 'react';
import { RefreshCw, UserPlus, UserX } from 'lucide-react';
import { useApp } from '../../context/AppContext';
import type { StaffPrincipal } from '../../types/admin';
import { ErrorBanner } from '../ErrorBanner';
import { LoadingSpinner } from '../LoadingSpinner';
import { EmptyState } from '../EmptyState';
import { formatDateTime } from './adminFormat';

const EMPTY_FORM = { subject: '', displayName: '', evidenceRef: '' };

export const AdminStaffPanel: React.FC = () => {
  const { client, runtimeCapabilities, staffProfile } = useApp();
  const oidc = runtimeCapabilities?.staffAuth.mode === 'OIDC';
  const issuer = runtimeCapabilities?.staffAuth.issuer || '';

  const [items, setItems] = useState<StaffPrincipal[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [form, setForm] = useState(EMPTY_FORM);
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [disabling, setDisabling] = useState<{ id: string; reason: string } | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setItems((await client.getStaffPrincipals()).items || []);
    } catch (err) {
      setError(err);
    } finally {
      setLoading(false);
    }
  }, [client]);

  useEffect(() => {
    load();
  }, [load]);

  const create = async (e: React.FormEvent) => {
    e.preventDefault();
    const subject = form.subject.trim();
    const displayName = form.displayName.trim();
    const evidenceRef = form.evidenceRef.trim();
    if (!subject || !displayName || !evidenceRef) {
      setFormError('OIDC subject, 표시 이름, 확인 근거를 모두 입력하세요.');
      return;
    }
    setSaving(true);
    setFormError(null);
    try {
      const created = await client.createStaffPrincipal({ issuer, subject, role: 'ADMIN', displayName, evidenceRef });
      setItems((prev) => [...prev, created]);
      setForm(EMPTY_FORM);
      setNotice(`관리자 '${created.displayName}' 계정을 등록했습니다.`);
    } catch (err) {
      setFormError(err instanceof Error ? err.message : '등록하지 못했습니다.');
    } finally {
      setSaving(false);
    }
  };

  const disable = async () => {
    if (!disabling) return;
    if (disabling.reason.trim().length < 2) {
      setFormError('비활성화 사유를 2자 이상 입력하세요.');
      return;
    }
    setSaving(true);
    setFormError(null);
    try {
      const updated = await client.disableStaffPrincipal(disabling.id, disabling.reason.trim());
      setItems((prev) => prev.map((p) => (p.principalId === updated.principalId ? updated : p)));
      setNotice(`'${updated.displayName}' 계정을 비활성화했습니다.`);
      setDisabling(null);
    } catch (err) {
      setFormError(err instanceof Error ? err.message : '비활성화하지 못했습니다.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="admin-panel" data-testid="admin-staff">
      <div className="admin-panel-header">
        <div>
          <h2>관리자 계정</h2>
          <p>
            기관 OIDC 로그인(issuer + subject)을 관리자 권한에 명시적으로 연결합니다. 이메일·이름으로 자동 연결하지
            않으며, 의사는 관리자 계정이 아니라 인증서로 로그인합니다.
          </p>
        </div>
        <button type="button" className="btn btn-outline" onClick={load} disabled={loading}>
          <RefreshCw size={16} /> 새로고침
        </button>
      </div>

      {error ? <ErrorBanner error={error as Error} onRetry={load} /> : null}
      {notice && (
        <div className="alert alert-info" role="status">
          {notice}
        </div>
      )}

      <div className="card">
        <div className="card-header">
          <span className="card-title">
            <UserPlus size={16} /> 관리자 등록
          </span>
        </div>
        <div className="card-body">
          {!oidc ? (
            <div className="alert alert-warning" data-testid="admin-staff-oidc-required">
              관리자 인증이 OIDC 모드일 때만 등록할 수 있습니다. 현재는 개발용 헤더 모드입니다.
            </div>
          ) : (
            <form className="admin-form-grid" onSubmit={create}>
              <label>
                OIDC 발급자
                <input className="form-input" value={issuer} readOnly />
              </label>
              <label>
                OIDC subject
                <input
                  className="form-input"
                  value={form.subject}
                  maxLength={255}
                  onChange={(e) => setForm({ ...form, subject: e.target.value })}
                  data-testid="staff-subject-input"
                />
              </label>
              <label>
                표시 이름
                <input
                  className="form-input"
                  value={form.displayName}
                  maxLength={100}
                  onChange={(e) => setForm({ ...form, displayName: e.target.value })}
                  data-testid="staff-name-input"
                />
              </label>
              <label>
                확인 근거 (재직·권한 승인 문서 번호 등)
                <input
                  className="form-input"
                  value={form.evidenceRef}
                  maxLength={200}
                  onChange={(e) => setForm({ ...form, evidenceRef: e.target.value })}
                  data-testid="staff-evidence-input"
                />
              </label>
              <div>
                <button type="submit" className="btn btn-primary" disabled={saving} data-testid="staff-create-btn">
                  관리자로 등록
                </button>
              </div>
            </form>
          )}
          {formError && !disabling && (
            <div className="alert alert-error" role="alert" style={{ marginTop: 12 }}>
              {formError}
            </div>
          )}
        </div>
      </div>

      <div className="card">
        <div className="card-header">
          <span className="card-title">등록된 관리자 ({items.length})</span>
        </div>
        <div className="card-body table-responsive">
          {loading && items.length === 0 ? (
            <LoadingSpinner message="관리자 목록을 불러오는 중..." />
          ) : items.length === 0 ? (
            <EmptyState title="등록된 관리자가 없습니다" message="OIDC 초기 등록 파일 또는 위 양식으로 등록합니다." />
          ) : (
            <table className="table">
              <thead>
                <tr>
                  <th>이름</th>
                  <th>OIDC subject</th>
                  <th>상태</th>
                  <th>근거</th>
                  <th>등록</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {items.map((p) => (
                  <tr key={p.principalId} data-testid={`staff-row-${p.principalId}`}>
                    <td>{p.displayName}</td>
                    <td>
                      <code>{p.subject}</code>
                    </td>
                    <td>
                      <span className={`badge ${p.status === 'ACTIVE' ? 'badge-success' : 'badge-failed'}`}>
                        {p.status === 'ACTIVE' ? '활성' : '비활성'}
                      </span>
                      {p.disabledReason && <div className="admin-hint">{p.disabledReason}</div>}
                    </td>
                    <td>{p.evidenceRef}</td>
                    <td>
                      {formatDateTime(p.createdAt)}
                      <div className="admin-hint">{p.createdBy}</div>
                    </td>
                    <td>
                      {p.status === 'ACTIVE' && p.principalId !== staffProfile?.principalId && (
                        disabling?.id === p.principalId ? (
                          <div className="admin-inline-form">
                            <input
                              className="form-input"
                              placeholder="비활성화 사유"
                              value={disabling.reason}
                              onChange={(e) => setDisabling({ ...disabling, reason: e.target.value })}
                              data-testid="staff-disable-reason"
                            />
                            <button type="button" className="btn btn-primary" onClick={disable} disabled={saving}>
                              확인
                            </button>
                            <button type="button" className="btn btn-outline" onClick={() => setDisabling(null)}>
                              취소
                            </button>
                            {formError && <div className="admin-hint admin-error">{formError}</div>}
                          </div>
                        ) : (
                          <button
                            type="button"
                            className="btn btn-outline"
                            onClick={() => {
                              setFormError(null);
                              setDisabling({ id: p.principalId, reason: '' });
                            }}
                            data-testid={`staff-disable-${p.principalId}`}
                          >
                            <UserX size={14} /> 비활성화
                          </button>
                        )
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  );
};
