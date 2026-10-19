import React, { useState } from 'react';
import { FileEdit, X } from 'lucide-react';
import { useApp } from '../context/AppContext';
import { Document } from '../types/api';
import { ErrorBanner } from './ErrorBanner';

interface DocumentAmendModalProps {
  document: Document;
  isOpen: boolean;
  onClose: () => void;
  onSuccess: (updatedDoc: Document) => void;
}

export const DocumentAmendModal: React.FC<DocumentAmendModalProps> = ({
  document,
  isOpen,
  onClose,
  onSuccess,
}) => {
  const { client, isMockMode, tenantId, actorId, setMockStore } = useApp();

  const [amendmentReason, setAmendmentReason] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<unknown>(null);

  if (!isOpen) return null;

  const latestVersion = document.latestVersion;

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files[0]) {
      const selected = e.target.files[0];
      if (selected.type !== 'application/pdf' && !selected.name.endsWith('.pdf')) {
        setError(new Error('PDF 형식의 파일만 업로드할 수 있습니다 (MIME: application/pdf).'));
        return;
      }
      setFile(selected);
      setError(null);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!file) {
      setError(new Error('정정 판독문 PDF 파일을 선택해주세요.'));
      return;
    }
    if (!amendmentReason.trim()) {
      setError(new Error('판독문 정정 사유를 반드시 입력해주세요.'));
      return;
    }

    const idempotencyKey = `doc-amend-${Date.now()}-${Math.random().toString(36).substring(2, 8)}`;
    setLoading(true);
    setError(null);

    try {
      if (isMockMode) {
        const nextVerNum = latestVersion.versionNumber + 1;
        const newVerId = `ver-mock-${Date.now()}-${nextVerNum}`;
        const mockSha256 = '8743b52063cd84097a65d1633f5c74f5ca64a2f4cc3ca52f3f98ed614bc498c4';
        const newVersion = {
          id: newVerId,
          documentId: document.id,
          versionNumber: nextVerNum,
          sha256: mockSha256,
          sizeBytes: file.size,
          contentType: 'application/pdf' as const,
          createdAt: new Date().toISOString(),
          createdBy: actorId,
          previousVersionId: latestVersion.id,
          amendmentReason: amendmentReason.trim(),
        };

        const updatedDoc: Document = {
          ...document,
          latestVersion: newVersion,
          versions: [...document.versions, newVersion],
          updatedAt: new Date().toISOString(),
        };

        setMockStore((prev) => ({
          ...prev,
          documents: {
            ...prev.documents,
            [tenantId]: (prev.documents[tenantId] || []).map((d) =>
              d.id === document.id ? updatedDoc : d
            ),
          },
        }));

        onSuccess(updatedDoc);
        onClose();
      } else {
        const updated = await client.amendDocument(
          document.id,
          file,
          latestVersion.id,
          amendmentReason.trim(),
          idempotencyKey
        );
        onSuccess(updated);
        onClose();
      }
    } catch (err) {
      setError(err);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-overlay" role="dialog" aria-modal="true" aria-labelledby="amend-modal-title">
      <div className="modal-content">
        <div className="modal-header">
          <h3 id="amend-modal-title" style={{ fontSize: '1.1rem', fontWeight: 700, color: 'var(--navy-900)' }}>
            판독문 정정본 등록 (버전 {latestVersion.versionNumber + 1})
          </h3>
          <button
            type="button"
            onClick={onClose}
            className="btn btn-secondary"
            style={{ padding: '4px 8px' }}
            aria-label="닫기"
          >
            <X size={16} />
          </button>
        </div>

        <form onSubmit={handleSubmit}>
          <div className="modal-body">
            <ErrorBanner error={error as Error} />

            <div className="alert alert-info">
              <div>
                <strong>버전 이력 관리:</strong> 기존 버전 (v{latestVersion.versionNumber})을 덮어쓰지 않고
                새 정정 버전을 추가합니다. 서명 전 최신 버전의 요청을 확인하십시오. 개발 서버를 재시작하면 데이터가 초기화됩니다.
              </div>
            </div>

            <div className="form-group">
              <label htmlFor="amend-prev-ver" className="form-label">기준 이전 버전 ID (previousVersionId)</label>
              <input
                id="amend-prev-ver"
                type="text"
                className="form-input"
                value={`${latestVersion.id} (v${latestVersion.versionNumber})`}
                disabled
                style={{ backgroundColor: 'var(--navy-100)', color: 'var(--navy-600)' }}
              />
            </div>

            <div className="form-group">
              <label htmlFor="amend-reason" className="form-label">
                정정 사유 (amendmentReason) <span style={{ color: 'var(--red-600)' }}>*</span>
              </label>
              <textarea
                id="amend-reason"
                className="form-textarea"
                rows={3}
                placeholder="예: 판독 소견 부위 기재 착오 정정 (우측 쇄골하 → 좌측 쇄골하)"
                value={amendmentReason}
                onChange={(e) => setAmendmentReason(e.target.value)}
                required
              />
            </div>

            <div className="form-group">
              <label htmlFor="amend-file" className="form-label">
                정정된 새 판독문 PDF <span style={{ color: 'var(--red-600)' }}>*</span>
              </label>
              <input
                id="amend-file"
                type="file"
                accept=".pdf,application/pdf"
                className="form-input"
                onChange={handleFileChange}
                required
              />
            </div>
          </div>

          <div className="modal-footer">
            <button type="button" onClick={onClose} className="btn btn-secondary" disabled={loading}>
              취소
            </button>
            <button type="submit" className="btn btn-primary" disabled={loading}>
              <FileEdit size={14} />
              {loading ? '정정본 등록 중...' : '정정 버전 업로드'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
