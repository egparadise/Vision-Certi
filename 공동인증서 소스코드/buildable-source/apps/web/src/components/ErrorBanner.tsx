import React from 'react';
import { AlertTriangle, RefreshCw } from 'lucide-react';
import { ApiClientError } from '../api/client';
import { ApiError } from '../types/api';

interface ErrorBannerProps {
  error: ApiClientError | ApiError | Error | string | null;
  onRetry?: () => void;
  className?: string;
}

export const ErrorBanner: React.FC<ErrorBannerProps> = ({ error, onRetry, className = '' }) => {
  if (!error) return null;

  let code = 'CLIENT_ERROR';
  let message = '오류가 발생했습니다.';
  let correlationId: string | null = null;
  let retryable = false;

  if (error instanceof ApiClientError) {
    code = error.code;
    message = error.message;
    correlationId = error.correlationId;
    retryable = error.retryable;
  } else if (typeof error === 'object' && 'code' in error && 'message' in error) {
    const apiErr = error as ApiError;
    code = apiErr.code;
    message = apiErr.message;
    correlationId = apiErr.correlationId;
    retryable = Boolean(apiErr.retryable);
  } else if (error instanceof Error) {
    message = error.message;
  } else if (typeof error === 'string') {
    message = error;
  }

  return (
    <div
      role="alert"
      className={`alert alert-error ${className}`}
      style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: '10px' }}>
        <AlertTriangle size={20} style={{ color: 'var(--red-600)', flexShrink: 0, marginTop: '2px' }} />
        <div>
          <div style={{ fontWeight: 700, fontSize: '0.9rem' }}>
            [{code}] {message}
          </div>
          {correlationId && correlationId !== 'none' && (
            <div style={{ fontSize: '0.75rem', color: 'var(--navy-600)', marginTop: '2px' }}>
              추적 ID (Correlation-Id): <code style={{ fontFamily: 'var(--font-mono)' }}>{correlationId}</code>
            </div>
          )}
          {retryable && (
            <div style={{ fontSize: '0.75rem', color: 'var(--amber-700)', marginTop: '2px' }}>
              * 일시적인 서버 오류로 재시도가 가능합니다.
            </div>
          )}
        </div>
      </div>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="btn btn-secondary"
          style={{ padding: '6px 12px', fontSize: '0.8rem', whiteSpace: 'nowrap' }}
        >
          <RefreshCw size={14} />
          재시도
        </button>
      )}
    </div>
  );
};
