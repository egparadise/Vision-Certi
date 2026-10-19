import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Lock, RefreshCw, RotateCcw, Save, SlidersHorizontal } from 'lucide-react';
import { useApp } from '../../context/AppContext';
import { ApiClientError } from '../../api/client';
import type { SettingGroup, SystemSettingView, SystemSettingsResponse } from '../../types/admin';
import { ErrorBanner } from '../ErrorBanner';
import { LoadingSpinner } from '../LoadingSpinner';
import { SETTING_GROUP_LABELS, UNIT_LABELS, formatDateTime, formatSettingValue } from './adminFormat';

type EditMode = 'change' | 'reset';

interface EditState {
  key: string;
  mode: EditMode;
  value: string;
  reason: string;
}

const GROUP_ORDER: SettingGroup[] = ['DOCUMENTS', 'SIGNING', 'PACS', 'DOCTORS', 'RATE_LIMITS'];

export const AdminSettingsPanel: React.FC = () => {
  const { client } = useApp();
  const [data, setData] = useState<SystemSettingsResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [edit, setEdit] = useState<EditState | null>(null);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await client.getSystemSettings());
    } catch (err) {
      setError(err);
    } finally {
      setLoading(false);
    }
  }, [client]);

  useEffect(() => {
    load();
  }, [load]);

  const groups = useMemo(() => {
    const items = data?.items ?? [];
    return GROUP_ORDER.map((group) => ({ group, items: items.filter((i) => i.group === group) })).filter(
      (g) => g.items.length > 0
    );
  }, [data]);

  const start = (item: SystemSettingView, mode: EditMode) => {
    setEdit({ key: item.key, mode, value: String(item.value), reason: '' });
    setFormError(null);
    setNotice(null);
  };

  const submit = async (item: SystemSettingView) => {
    if (!edit) return;
    const reason = edit.reason.trim();
    if (reason.length < 2) {
      setFormError('변경 사유를 2자 이상 입력하세요.');
      return;
    }
    let value = 0;
    if (edit.mode === 'change') {
      value = Number(edit.value);
      if (!Number.isInteger(value) || value < item.min || value > item.max) {
        setFormError(`${item.min.toLocaleString('ko-KR')} ~ ${item.max.toLocaleString('ko-KR')} 범위의 정수를 입력하세요.`);
        return;
      }
      if (value === item.value) {
        setFormError('현재 값과 같습니다.');
        return;
      }
    }
    setSaving(true);
    setFormError(null);
    try {
      const updated =
        edit.mode === 'change'
          ? await client.updateSystemSetting(item.key, { value, reason, expectedRevision: item.revision })
          : await client.resetSystemSetting(item.key, { reason, expectedRevision: item.revision });
      setData((prev) =>
        prev ? { ...prev, items: prev.items.map((i) => (i.key === updated.key ? updated : i)) } : prev
      );
      setNotice(
        `'${item.label}' 값을 ${formatSettingValue(item.unit, updated.value)}(으)로 ${
          edit.mode === 'change' ? '변경' : '기본값 복원'
        }했습니다. 감사 이벤트에 기록되었습니다.`
      );
      setEdit(null);
    } catch (err) {
      if (err instanceof ApiClientError && err.code === 'SETTING_CHANGED_CONCURRENTLY') {
        setFormError('다른 관리자가 먼저 변경했습니다. 최신 값을 불러왔으니 다시 확인하세요.');
        await load();
      } else {
        setFormError(err instanceof Error ? err.message : '저장하지 못했습니다.');
      }
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="admin-panel" data-testid="admin-settings">
      <div className="admin-panel-header">
        <div>
          <h2>운영 정책 설정</h2>
          <p>
            시스템 전체에 바로 적용되는 운영 값입니다. 허용 범위 안에서만 바꿀 수 있고, 모든 변경은 사유와 함께 감사
            이벤트에 기록됩니다. 보안 경계·접속 주소·비밀키는 여기서 바꿀 수 없으며 &lsquo;시스템 구성&rsquo;에서 확인합니다.
          </p>
        </div>
        <button type="button" className="btn btn-outline" onClick={load} disabled={loading}>
          <RefreshCw size={16} /> 새로고침
        </button>
      </div>

      {error ? <ErrorBanner error={error as Error} onRetry={load} /> : null}
      {notice && (
        <div className="alert alert-info" role="status" data-testid="admin-settings-notice">
          {notice}
        </div>
      )}
      {data && !data.editable && (
        <div className="alert alert-warning" role="note" data-testid="admin-settings-readonly">
          <Lock size={16} /> {data.editableReason || '읽기 전용입니다.'}
        </div>
      )}

      {loading && !data ? (
        <LoadingSpinner message="운영 정책을 불러오는 중..." />
      ) : (
        groups.map(({ group, items }) => (
          <div className="card" key={group}>
            <div className="card-header">
              <span className="card-title">
                <SlidersHorizontal size={16} /> {SETTING_GROUP_LABELS[group]}
              </span>
            </div>
            <div className="card-body admin-setting-list">
              {items.map((item) => {
                const editing = edit?.key === item.key;
                return (
                  <div className="admin-setting" key={item.key} data-testid={`setting-${item.key}`}>
                    <div className="admin-setting-main">
                      <div className="admin-setting-title">
                        {item.label}
                        {item.overridden ? (
                          <span className="badge badge-pending">관리자 변경</span>
                        ) : (
                          <span className="badge badge-planned">배포 기본값</span>
                        )}
                      </div>
                      <div className="admin-setting-desc">{item.description}</div>
                      <div className="admin-setting-meta">
                        현재 <strong data-testid={`setting-value-${item.key}`}>{formatSettingValue(item.unit, item.value)}</strong>
                        {' · '}기본값 {formatSettingValue(item.unit, item.defaultValue)}
                        {' · '}허용 {formatSettingValue(item.unit, item.min)} ~ {formatSettingValue(item.unit, item.max)}
                        {item.overridden && (
                          <>
                            {' · '}
                            {formatDateTime(item.updatedAt)} {item.updatedBy}
                            {item.updatedByTenant ? `(${item.updatedByTenant})` : ''}: {item.reason}
                          </>
                        )}
                      </div>
                    </div>
                    {data?.editable && !editing && (
                      <div className="admin-setting-actions">
                        <button
                          type="button"
                          className="btn btn-secondary"
                          onClick={() => start(item, 'change')}
                          data-testid={`setting-edit-${item.key}`}
                        >
                          변경
                        </button>
                        {item.overridden && (
                          <button
                            type="button"
                            className="btn btn-outline"
                            onClick={() => start(item, 'reset')}
                            data-testid={`setting-reset-${item.key}`}
                          >
                            <RotateCcw size={14} /> 기본값 복원
                          </button>
                        )}
                      </div>
                    )}
                    {editing && edit && (
                      <form
                        className="admin-setting-form"
                        noValidate
                        onSubmit={(e) => {
                          e.preventDefault();
                          submit(item);
                        }}
                      >
                        {edit.mode === 'change' && (
                          <label>
                            새 값 ({UNIT_LABELS[item.unit]})
                            <input
                              className="form-input"
                              type="number"
                              min={item.min}
                              max={item.max}
                              step={1}
                              value={edit.value}
                              onChange={(e) => setEdit({ ...edit, value: e.target.value })}
                              data-testid={`setting-input-${item.key}`}
                            />
                            {edit.value !== '' && Number.isFinite(Number(edit.value)) && (
                              <span className="admin-hint">= {formatSettingValue(item.unit, Number(edit.value))}</span>
                            )}
                          </label>
                        )}
                        <label>
                          {edit.mode === 'change' ? '변경 사유' : '복원 사유'} (필수)
                          <input
                            className="form-input"
                            value={edit.reason}
                            maxLength={200}
                            onChange={(e) => setEdit({ ...edit, reason: e.target.value })}
                            placeholder="예: 원격판독 승인 대기 시간을 운영 기준에 맞춤"
                            data-testid={`setting-reason-${item.key}`}
                          />
                        </label>
                        {formError && (
                          <div className="alert alert-error" role="alert">
                            {formError}
                          </div>
                        )}
                        <div className="admin-setting-actions">
                          <button
                            type="submit"
                            className="btn btn-primary"
                            disabled={saving}
                            data-testid={`setting-save-${item.key}`}
                          >
                            <Save size={14} /> {edit.mode === 'change' ? '저장' : '기본값으로 복원'}
                          </button>
                          <button type="button" className="btn btn-outline" onClick={() => setEdit(null)} disabled={saving}>
                            취소
                          </button>
                        </div>
                      </form>
                    )}
                  </div>
                );
              })}
            </div>
          </div>
        ))
      )}
    </div>
  );
};
