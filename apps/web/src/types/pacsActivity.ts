export interface PacsActivityPage {
  totals: { requested: number; attempted: number; verified: number; redeemed: number; checked: number; denied: number;
    loginAttempted?: number; loginSucceeded?: number; loginFailed?: number; approvalRequests?: number; approvalDocuments?: number };
  certificates: Array<{ fingerprintSha256: string; subjectDn: string | null; issuerDn: string | null;
    notAfter: string | null; verified: number; redeemed: number; checked: number; lastSeenAt: string;
    attempted?: number; loginAttempted?: number; loginSucceeded?: number; loginFailed?: number; approvalRequests?: number;
    approvalDocuments?: number; accounts?: string[]; computers?: string[] }>;
  events: Array<{ id: string; occurredAt: string; action: string; outcome: string; correlationId: string | null;
    targetId: string; details: Record<string, string>; legacy: boolean }>;
  total: number; offset: number; limit: number;
}
