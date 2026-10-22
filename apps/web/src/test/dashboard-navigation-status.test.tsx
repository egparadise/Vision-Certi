import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CertificateServerStatus } from '../components/CertificateServerStatus';
import { DashboardView } from '../components/DashboardView';

const state = vi.hoisted(() => ({ client: { getHealth: vi.fn(), getDashboard: vi.fn() }, isMockMode: false,
  tenantId: 'test', role: 'DOCTOR', setActiveTab: vi.fn(), setSelectedDocumentId: vi.fn() }));
vi.mock('../context/AppContext', () => ({ useApp: () => state }));
beforeEach(() => {
  vi.clearAllMocks(); state.isMockMode = false;
  state.client.getHealth.mockResolvedValue({ status: 'UP', service: 'vision-certi-backend' });
  state.client.getDashboard.mockResolvedValue({ documents: 2, documentVersions: 3, signatures: 1, auditEvents: 4, deliveriesByStatus: { QUEUED: 2 }, signRequestsByStatus: {} });
});
afterEach(() => { cleanup(); vi.useRealTimers(); });

describe('server connectivity and dashboard navigation', () => {
  it('refreshes live health, shows failed connectivity and recovery, stops when unmounted', async () => {
    vi.useFakeTimers();
    const view = render(<CertificateServerStatus />);
    await act(async () => {});
    expect(screen.getByRole('status')).toHaveTextContent('인증서버 ON');
    state.client.getHealth.mockRejectedValueOnce(new Error('network'));
    await act(async () => { vi.advanceTimersByTime(30_000); });
    expect(screen.getByRole('status')).toHaveTextContent('연결 안 됨');
    await act(async () => { vi.advanceTimersByTime(30_000); });
    expect(screen.getByRole('status')).toHaveTextContent('인증서버 ON');
    view.unmount();
    await act(async () => { vi.advanceTimersByTime(60_000); });
    expect(state.client.getHealth).toHaveBeenCalledTimes(3);
  });
  it('never reports online for a wrong API response or mock mode', async () => {
    state.client.getHealth.mockResolvedValue({ status: 'UP', service: 'some-other-service' });
    const view = render(<CertificateServerStatus />);
    await act(async () => {});
    expect(screen.getByRole('status')).toHaveTextContent('연결 안 됨');
    state.isMockMode = true;
    view.rerender(<CertificateServerStatus />);
    expect(screen.getByRole('status')).toHaveTextContent('모의 모드');
    expect(state.client.getHealth).toHaveBeenCalledTimes(1);
  });
  it('times out a stalled request and cancels a pending request on unmount', async () => {
    vi.useFakeTimers();
    state.client.getHealth.mockImplementation((signal: AbortSignal) => new Promise((_, reject) => signal.addEventListener('abort', () => reject(new Error('aborted')))));
    const view = render(<CertificateServerStatus />);
    await act(async () => { vi.advanceTimersByTime(5_000); });
    expect(screen.getByRole('status')).toHaveTextContent('연결 안 됨');
    await act(async () => { vi.advanceTimersByTime(25_000); });
    const signal = state.client.getHealth.mock.calls[1][0];
    view.unmount();
    expect(signal.aborted).toBe(true);
  });
  it('navigates all four metric cards while help stays independent', async () => {
    render(<DashboardView />);
    await act(async () => {});
    fireEvent.click(screen.getByRole('button', { name: '등록 판독문 도움말 보기' }));
    expect(screen.getByRole('dialog', { name: '등록 판독문' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '안내 닫기' }));
    expect(state.setActiveTab).not.toHaveBeenCalled();
    for (const [name, tab] of [['등록 판독문 2건 · 목록으로 이동', 'documents'], ['전자서명 완료 1건 · 서명 내역으로 이동', 'signatures'], ['전송 대기 2건 · 전송 큐로 이동', 'deliveries'], ['감사 이벤트 4건 · 감사 로그로 이동', 'audit']]) {
      fireEvent.click(screen.getByRole('button', { name }));
      expect(state.setActiveTab).toHaveBeenLastCalledWith(tab);
    }
    expect(state.setSelectedDocumentId).toHaveBeenCalledWith(null);
  });
});
