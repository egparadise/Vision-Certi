import React, { useState } from 'react';
import { Upload, X } from 'lucide-react';
import { useApp } from '../context/AppContext';
import { Document, DocumentMetadata } from '../types/api';
import { ErrorBanner } from './ErrorBanner';

interface DocumentUploadModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: (newDoc: Document) => void;
}

export const DocumentUploadModal: React.FC<DocumentUploadModalProps> = ({
  isOpen,
  onClose,
  onSuccess,
}) => {
  const { client, isMockMode, tenantId, actorId, setMockStore } = useApp();

  const [title, setTitle] = useState('');
  const [externalDocumentId, setExternalDocumentId] = useState('');
  const [externalStudyId, setExternalStudyId] = useState('');
  const [signerId, setSignerId] = useState('doctor-demo');
  const [file, setFile] = useState<File | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<unknown>(null);

  if (!isOpen) return null;

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
      setError(new Error('업로드할 판독문 PDF 파일을 선택해주세요.'));
      return;
    }
    if (!title.trim() || !externalDocumentId.trim() || !externalStudyId.trim()) {
      setError(new Error('모든 필수 항목을 입력해주세요.'));
      return;
    }

    const metadata: DocumentMetadata = {
      externalDocumentId: externalDocumentId.trim(),
      externalStudyId: externalStudyId.trim(),
      title: title.trim(),
      signerId: signerId.trim(),
    };

    const idempotencyKey = `doc-reg-${Date.now()}-${Math.random().toString(36).substring(2, 8)}`;
    setLoading(true);
    setError(null);

    try {
      if (isMockMode) {
        // Create mock document
        const newDocId = `doc-mock-${Date.now()}`;
        const newVerId = `ver-mock-${Date.now()}-1`;
        const mockSha256 = '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08';
        const newDoc: Document = {
          id: newDocId,
          tenantId,
          externalDocumentId: metadata.externalDocumentId,
          externalStudyId: metadata.externalStudyId,
          title: metadata.title,
          signerId: metadata.signerId,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
          createdBy: actorId,
          latestVersion: {
            id: newVerId,
            documentId: newDocId,
            versionNumber: 1,
            sha256: mockSha256,
            sizeBytes: file.size,
            contentType: 'application/pdf',
            createdAt: new Date().toISOString(),
            createdBy: actorId,
            previousVersionId: null,
            amendmentReason: null,
          },
          versions: [
            {
              id: newVerId,
              documentId: newDocId,
              versionNumber: 1,
              sha256: mockSha256,
              sizeBytes: file.size,
              contentType: 'application/pdf',
              createdAt: new Date().toISOString(),
              createdBy: actorId,
              previousVersionId: null,
              amendmentReason: null,
            },
          ],
        };

        setMockStore((prev) => ({
          ...prev,
          documents: {
            ...prev.documents,
            [tenantId]: [newDoc, ...(prev.documents[tenantId] || [])],
          },
        }));

        onSuccess(newDoc);
        onClose();
      } else {
        const created = await client.registerDocument(file, metadata, idempotencyKey);
        onSuccess(created);
        onClose();
      }
    } catch (err) {
      setError(err);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="modal-overlay" role="dialog" aria-modal="true" aria-labelledby="upload-modal-title">
      <div className="modal-content">
        <div className="modal-header">
          <h3 id="upload-modal-title" style={{ fontSize: '1.1rem', fontWeight: 700, color: 'var(--navy-900)' }}>
            새 판독문 원본 등록 (PDF Multipart)
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

            <div className="form-group">
              <label htmlFor="upload-title" className="form-label">
                판독문 제목 <span style={{ color: 'var(--red-600)' }}>*</span>
              </label>
              <input
                id="upload-title"
                type="text"
                className="form-input"
                placeholder="예: [정밀] 뇌 자기공명영상(MRI) 정밀 판독소견서"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                required
              />
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
              <div className="form-group">
                <label htmlFor="upload-doc-id" className="form-label">
                  외부 문서 ID (externalDocumentId) <span style={{ color: 'var(--red-600)' }}>*</span>
                </label>
                <input
                  id="upload-doc-id"
                  type="text"
                  className="form-input"
                  placeholder="예: DOC-2026-X01"
                  value={externalDocumentId}
                  onChange={(e) => setExternalDocumentId(e.target.value)}
                  required
                />
              </div>

              <div className="form-group">
                <label htmlFor="upload-study-id" className="form-label">
                  검사 번호 (externalStudyId) <span style={{ color: 'var(--red-600)' }}>*</span>
                </label>
                <input
                  id="upload-study-id"
                  type="text"
                  className="form-input"
                  placeholder="예: STUDY-MRI-0982"
                  value={externalStudyId}
                  onChange={(e) => setExternalStudyId(e.target.value)}
                  required
                />
              </div>
            </div>

            <div className="form-group">
              <label htmlFor="upload-signer-id" className="form-label">
                판독 지정의 (signerId) <span style={{ color: 'var(--red-600)' }}>*</span>
              </label>
              <select
                id="upload-signer-id"
                className="form-select"
                value={signerId}
                onChange={(e) => setSignerId(e.target.value)}
              >
                <option value="doctor-demo">doctor-demo (김판독 전문의)</option>
              </select>
              <div style={{ fontSize: '0.75rem', color: 'var(--navy-500)', marginTop: '4px' }}>
                * 개발 모드에서는 기관에 등록된 <code>doctor-demo</code>만 지정 가능합니다.
              </div>
            </div>

            <div className="form-group">
              <label htmlFor="upload-pdf-file" className="form-label">
                판독문 PDF 원본 파일 <span style={{ color: 'var(--red-600)' }}>*</span>
              </label>
              <input
                id="upload-pdf-file"
                type="file"
                accept=".pdf,application/pdf"
                className="form-input"
                onChange={handleFileChange}
                required
              />
              <div style={{ fontSize: '0.75rem', color: 'var(--navy-500)', marginTop: '4px' }}>
                * PDF 20MiB 이하, %PDF- 표준 헤더 검사 수행 (브라우저 로컬 저장소에는 저장되지 않습니다).
              </div>
            </div>
          </div>

          <div className="modal-footer">
            <button type="button" onClick={onClose} className="btn btn-secondary" disabled={loading}>
              취소
            </button>
            <button type="submit" className="btn btn-primary" disabled={loading}>
              <Upload size={14} />
              {loading ? '서버 등록 중...' : '판독문 원본 업로드'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
