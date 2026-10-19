export type VcApprovalState =
  | 'PREPARING'
  | 'PREPARE_RETRY'
  | 'PENDING_SIGNATURE'
  | 'SIGNED_REMOTE'
  | 'APPROVED'
  | 'EXPIRED'
  | 'REMOTE_FAILED'
  | 'SUPERSEDED'
  | 'SUPERSEDED_AFTER_SIGNATURE'
  | 'COMMIT_REFUSED';

export interface VcConfig {
  enabled: boolean;
  state: 'DISABLED' | 'ENABLED' | 'MISCONFIGURED';
  visionCertiBaseUrl: string | null;
  audience: string | null;
  tenantId: string | null;
}

export interface VcSessionData {
  tenantId: string;
  doctorId: string;
  certificateEnrollmentId: string;
  certificateFingerprintSha256: string;
  identityMode: string;
  sessionExpiresAt: string;
}

export interface VcCertificateLoginResponse {
  token: string;
  expiresAt: string;
  user_data: {
    user_idx: number;
    user_id: string;
    user_name: string;
    group_level: number;
    doctor_license_number: string | null;
  };
  vc: VcSessionData;
}

export interface VcApprovalSignature {
  signatureId: string;
  algorithm: string;
  signatureMode: string;
  signatureValueBase64: string;
  signedAt: string | null;
  certificateEnrollmentId: string;
  certificateFingerprintSha256: string;
  certificateSerial: string;
  keyName: string;
  keyVersion: number;
  publicKeySpki: string;
}

export interface VcApproval {
  approvalId: string;
  studyIdx: number;
  reportIdx: number;
  revision: number;
  targetStatus: 'A' | 'RA';
  state: VcApprovalState;
  stateReason: string | null;
  approved: boolean;
  retryable: boolean;
  userIdx: number;
  vcTenantId: string;
  vcDoctorId: string;
  contentSha256: string;
  snapshotSha256: string;
  supersedesApprovalId: string | null;
  attemptCount: number;
  lastErrorCode: string | null;
  visionCertiSignRequestId: string | null;
  visionCertiDocumentVersionId: string | null;
  signRequestExpiresAt: string | null;
  signature: VcApprovalSignature | null;
  createdAt: string;
  updatedAt: string;
  committedAt: string | null;
}

export interface VcCurrentReportResponse {
  contentSha256: string;
  report: {
    reportId: number;
    studyId: number;
    reading: string;
    conclusion: string;
  };
}

export interface VcPrepareResponse {
  approval: VcApproval;
  snapshot: string;
  snapshotSha256: string;
  visionCertiSignRequestId: string;
}

export interface VcFinalizeResponse {
  approval: VcApproval;
}

export interface VcDoctorLink {
  linkIdx: number;
  userIdx: number;
  vcTenantId: string;
  vcDoctorId: string;
  status: 'ACTIVE' | 'REVOKED';
  evidenceRef: string;
  note: string | null;
  createdByUserIdx: number;
  createdAt: string;
  revokedByUserIdx: number | null;
  revokedAt: string | null;
  revokeReason: string | null;
}

export interface CreateDoctorLinkRequest {
  user_idx: number;
  vc_tenant_id: string;
  vc_doctor_id: string;
  evidence_ref: string;
  note?: string;
}
