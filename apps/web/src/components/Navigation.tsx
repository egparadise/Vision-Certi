import React from 'react';
import {
  LayoutDashboard,
  ClipboardCheck,
  FileText,
  KeyRound,
  Send,
  History,
  Award,
} from 'lucide-react';
import { NavTab, useApp } from '../context/AppContext';

interface TabItem {
  id: NavTab;
  label: string;
  icon: React.ReactNode;
  badge?: string;
}

export const Navigation: React.FC = () => {
  const { activeTab, setActiveTab, role } = useApp();

  const tabs: TabItem[] = [
    {
      id: 'dashboard',
      label: '운영 대시보드',
      icon: <LayoutDashboard size={16} />,
    },
    {
      id: 'review',
      label: '의사 판독 승인 대기',
      icon: <ClipboardCheck size={16} />,
      badge: role === 'DOCTOR' ? '의사 전용' : '제한됨',
    },
    {
      id: 'documents',
      label: '판독문 목록 및 등록',
      icon: <FileText size={16} />,
    },
    {
      id: 'signatures',
      label: '서명 내역 및 검증 포털',
      icon: <KeyRound size={16} />,
    },
    {
      id: 'deliveries',
      label: '병원 전송 큐 (Outbox)',
      icon: <Send size={16} />,
    },
    {
      id: 'audit',
      label: '감사 이벤트 (Audit Trail)',
      icon: <History size={16} />,
    },
    {
      id: 'certificates',
      label: '인증서 관리 (CA/HSM)',
      icon: <Award size={16} />,
      badge: '준비 중',
    },
  ];

  return (
    <nav className="nav-bar" aria-label="메인 네비게이션">
      <div className="nav-inner">
        {tabs.map((tab) => {
          const isActive = activeTab === tab.id;
          return (
            <button
              key={tab.id}
              type="button"
              className={`nav-tab ${isActive ? 'active' : ''}`}
              onClick={() => {
                setActiveTab(tab.id);
                if (window.location.hash) {
                  window.location.hash = '';
                }
              }}
              aria-current={isActive ? 'page' : undefined}
            >
              {tab.icon}
              <span>{tab.label}</span>
              {tab.badge && (
                <span
                  style={{
                    fontSize: '0.65rem',
                    padding: '1px 5px',
                    borderRadius: '4px',
                    backgroundColor:
                      tab.badge === '준비 중'
                        ? 'rgba(255, 255, 255, 0.15)'
                        : tab.badge === '의사 전용'
                        ? 'rgba(45, 212, 191, 0.2)'
                        : 'rgba(239, 68, 68, 0.2)',
                    color:
                      tab.badge === '준비 중'
                        ? '#cbd5e1'
                        : tab.badge === '의사 전용'
                        ? '#2dd4bf'
                        : '#fca5a5',
                  }}
                >
                  {tab.badge}
                </span>
              )}
            </button>
          );
        })}
      </div>
    </nav>
  );
};
