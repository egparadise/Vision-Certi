import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ErrorBanner } from '../components/ErrorBanner';
import { ApiClientError } from '../api/client';

describe('Server Error Display and Retry Tests', () => {
  it('renders ApiClientError with code, message, and correlationId', () => {
    const error = new ApiClientError(409, {
      code: 'DOCUMENT_HASH_MISMATCH',
      message: '서명 대상 문서의 해시와 등록 원본 해시가 일치하지 않습니다.',
      correlationId: 'corr-err-998811',
      retryable: false,
    });

    render(<ErrorBanner error={error} />);

    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByText(/DOCUMENT_HASH_MISMATCH/)).toBeInTheDocument();
    expect(
      screen.getByText(/서명 대상 문서의 해시와 등록 원본 해시가 일치하지 않습니다/)
    ).toBeInTheDocument();
    expect(screen.getByText('corr-err-998811')).toBeInTheDocument();
  });

  it('renders retryable server errors with retry button and calls onRetry', () => {
    const error = new ApiClientError(503, {
      code: 'BACKEND_TEMPORARY_UNAVAILABLE',
      message: '백엔드 서버 일시 지연',
      correlationId: 'corr-503-abc',
      retryable: true,
    });

    const onRetryMock = vi.fn();
    render(<ErrorBanner error={error} onRetry={onRetryMock} />);

    expect(screen.getByText(/일시적인 서버 오류로 재시도가 가능합니다/)).toBeInTheDocument();
    const retryBtn = screen.getByRole('button', { name: /재시도/i });
    expect(retryBtn).toBeInTheDocument();

    fireEvent.click(retryBtn);
    expect(onRetryMock).toHaveBeenCalledTimes(1);
  });

  it('returns null when error is null', () => {
    const { container } = render(<ErrorBanner error={null} />);
    expect(container.firstChild).toBeNull();
  });
});
