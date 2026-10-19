import React from 'react';
import { Inbox } from 'lucide-react';

interface EmptyStateProps {
  title?: string;
  message?: string;
  actionText?: string;
  onAction?: () => void;
  icon?: React.ReactNode;
}

export const EmptyState: React.FC<EmptyStateProps> = ({
  title = '데이터가 없습니다',
  message = '등록된 내역이 존재하지 않거나 현재 조건에 맞는 항목이 없습니다.',
  actionText,
  onAction,
  icon,
}) => {
  return (
    <div
      style={{
        textAlign: 'center',
        padding: '48px 20px',
        backgroundColor: 'white',
        borderRadius: '8px',
        border: '1px dashed var(--navy-300)',
        margin: '16px 0',
      }}
    >
      <div style={{ color: 'var(--navy-400)', marginBottom: '12px' }}>
        {icon || <Inbox size={40} style={{ margin: '0 auto', color: 'var(--navy-400)' }} />}
      </div>
      <h3 style={{ fontSize: '1rem', fontWeight: 700, color: 'var(--navy-800)', marginBottom: '6px' }}>
        {title}
      </h3>
      <p style={{ fontSize: '0.85rem', color: 'var(--navy-500)', maxWidth: '420px', margin: '0 auto 16px' }}>
        {message}
      </p>
      {actionText && onAction && (
        <button type="button" onClick={onAction} className="btn btn-primary">
          {actionText}
        </button>
      )}
    </div>
  );
};
