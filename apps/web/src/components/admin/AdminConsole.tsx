import React from 'react';
import {
  History,
  LayoutDashboard,
  Send,
  Server,
  ShieldAlert,
  SlidersHorizontal,
  Stethoscope,
  UserCog,
} from 'lucide-react';
import { useApp } from '../../context/AppContext';
import { DoctorAdminView } from '../DoctorAdminView';
import { AuditEventsView } from '../AuditEventsView';
import { DeliveryQueueView } from '../DeliveryQueueView';
import { AdminOverview } from './AdminOverview';
import { AdminSettingsPanel } from './AdminSettingsPanel';
import { AdminStaffPanel } from './AdminStaffPanel';
import { AdminSystemPanel } from './AdminSystemPanel';
import './admin.css';

export type AdminSection = 'overview' | 'doctors' | 'staff' | 'settings' | 'system' | 'audit' | 'deliveries';

const SECTIONS: { id: AdminSection; label: string; description: string; icon: React.ReactNode }[] = [
  { id: 'overview', label: '개요', description: '운영 상태·구성 경고', icon: <LayoutDashboard size={16} /> },
  { id: 'doctors', label: '의사 관리', description: '가입 현황·인증서 검토·정지/폐기', icon: <Stethoscope size={16} /> },
  { id: 'staff', label: '관리자 계정', description: 'OIDC 관리자 권한 연결', icon: <UserCog size={16} /> },
  { id: 'settings', label: '운영 정책', description: '유효 시간·한도 설정', icon: <SlidersHorizontal size={16} /> },
  { id: 'system', label: '시스템 구성', description: '배포 구성(읽기 전용)', icon: <Server size={16} /> },
  { id: 'audit', label: '감사 로그', description: '행위 기록·해시 체인', icon: <History size={16} /> },
  { id: 'deliveries', label: '전송 큐', description: '병원 전송 상태', icon: <Send size={16} /> },
];

export function adminSectionFromHash(hash: string): AdminSection {
  const id = hash.replace(/^#\/admin\/?/, '').split(/[/?]/)[0];
  return (SECTIONS.find((s) => s.id === id)?.id ?? 'overview') as AdminSection;
}

export const AdminConsole: React.FC<{ section: AdminSection }> = ({ section }) => {
  const { role, isMockMode, tenantId } = useApp();

  const go = (next: AdminSection) => {
    window.location.hash = next === 'overview' ? '#/admin' : `#/admin/${next}`;
  };

  if (role !== 'ADMIN') {
    return (
      <div className="alert alert-error admin-denied" role="alert" data-testid="admin-access-denied">
        <ShieldAlert size={18} />
        <div>
          <strong>관리자 권한이 필요합니다.</strong>
          <div>관리자 모드는 관리자(ADMIN) 계정으로 로그인한 경우에만 사용할 수 있습니다.</div>
        </div>
      </div>
    );
  }

  return (
    <div className="admin-console" data-testid="admin-console">
      <aside className="admin-sidebar" aria-label="관리자 메뉴">
        <div className="admin-sidebar-title">
          관리자 모드
          <span>{tenantId}</span>
        </div>
        {SECTIONS.map((s) => (
          <button
            key={s.id}
            type="button"
            className={`admin-nav-item ${section === s.id ? 'active' : ''}`}
            aria-current={section === s.id ? 'page' : undefined}
            onClick={() => go(s.id)}
            data-testid={`admin-nav-${s.id}`}
          >
            {s.icon}
            <span>
              <strong>{s.label}</strong>
              <small>{s.description}</small>
            </span>
          </button>
        ))}
      </aside>
      <section className="admin-content">
        {isMockMode && section !== 'audit' && section !== 'deliveries' ? (
          <div className="alert alert-warning" role="note" data-testid="admin-mock-notice">
            모의(DEV MOCK) 모드에서는 관리자 기능을 사용할 수 없습니다. 실제 API에 연결한 뒤 이용하세요.
          </div>
        ) : (
          <>
            {section === 'overview' && <AdminOverview onNavigate={go} />}
            {section === 'doctors' && <DoctorAdminView />}
            {section === 'staff' && <AdminStaffPanel />}
            {section === 'settings' && <AdminSettingsPanel />}
            {section === 'system' && <AdminSystemPanel />}
            {section === 'audit' && <AuditEventsView />}
            {section === 'deliveries' && <DeliveryQueueView />}
          </>
        )}
      </section>
    </div>
  );
};
