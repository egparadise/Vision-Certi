import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { KeyRound, RefreshCw, Server } from 'lucide-react';
import { useApp } from '../../context/AppContext';
import type { SystemConfigurationResponse } from '../../types/admin';
import { ErrorBanner } from '../ErrorBanner';
import { LoadingSpinner } from '../LoadingSpinner';
import { CONFIG_GROUP_LABELS, formatDateTime } from './adminFormat';

export const AdminSystemPanel: React.FC = () => {
  const { client } = useApp();
  const [data, setData] = useState<SystemConfigurationResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await client.getSystemConfiguration());
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
    const order: string[] = [];
    for (const item of data?.items ?? []) {
      if (!order.includes(item.group)) order.push(item.group);
    }
    return order.map((group) => ({ group, items: (data?.items ?? []).filter((i) => i.group === group) }));
  }, [data]);

  return (
    <div className="admin-panel" data-testid="admin-system">
      <div className="admin-panel-header">
        <div>
          <h2>시스템 구성</h2>
          <p>
            배포 환경에서 정하는 구성입니다. 보안 경계·외부 연결·비밀키이므로 화면에서 바꿀 수 없고, 표시된 환경변수를
            바꾼 뒤 서버를 다시 시작해야 적용됩니다. 비밀키와 파일 경로는 설정 여부만 표시합니다.
          </p>
        </div>
        <button type="button" className="btn btn-outline" onClick={load} disabled={loading}>
          <RefreshCw size={16} /> 새로고침
        </button>
      </div>

      {error ? <ErrorBanner error={error as Error} onRetry={load} /> : null}

      {loading && !data ? (
        <LoadingSpinner message="시스템 구성을 불러오는 중..." />
      ) : data ? (
        <>
          <div className="detail-grid admin-system-summary">
            <div>
              <span className="metric-label">활성 프로파일</span>
              <strong>{data.activeProfiles.join(', ') || '-'}</strong>
            </div>
            <div>
              <span className="metric-label">저장소</span>
              <strong>{data.persistenceMode}</strong>
            </div>
            <div>
              <span className="metric-label">운영 정책 변경 가능 기관</span>
              <strong>{data.settingsEditorTenants.join(', ') || '없음(읽기 전용)'}</strong>
            </div>
            <div>
              <span className="metric-label">조회 시각</span>
              <strong>{formatDateTime(data.generatedAt)}</strong>
            </div>
          </div>
          {groups.map(({ group, items }) => (
            <div className="card" key={group}>
              <div className="card-header">
                <span className="card-title">
                  <Server size={16} /> {CONFIG_GROUP_LABELS[group] || group}
                </span>
              </div>
              <div className="card-body table-responsive">
                <table className="table">
                  <thead>
                    <tr>
                      <th>항목</th>
                      <th>값</th>
                      <th>환경변수</th>
                    </tr>
                  </thead>
                  <tbody>
                    {items.map((item) => (
                      <tr key={item.key} data-testid={`config-${item.key}`}>
                        <td>{item.label}</td>
                        <td>
                          {item.secret ? (
                            <span className={`badge ${item.configured ? 'badge-success' : 'badge-planned'}`}>
                              <KeyRound size={12} /> {item.configured ? '설정됨(값 비공개)' : '설정 안 됨'}
                            </span>
                          ) : item.configured ? (
                            <code>{item.value}</code>
                          ) : (
                            <span className="badge badge-planned">설정 안 됨</span>
                          )}
                        </td>
                        <td>
                          <code className="admin-env">{item.envVar}</code>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          ))}
        </>
      ) : null}
    </div>
  );
};
