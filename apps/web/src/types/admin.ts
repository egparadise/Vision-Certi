// Admin mode (관리자 모드) API types: runtime operational settings, deployment configuration, staff principals.

export type SettingGroup = 'DOCUMENTS' | 'SIGNING' | 'PACS' | 'DOCTORS' | 'RATE_LIMITS';
export type SettingUnit = 'BYTES' | 'SECONDS' | 'COUNT';

export interface SystemSettingView {
  key: string;
  group: SettingGroup;
  unit: SettingUnit;
  label: string;
  description: string;
  min: number;
  max: number;
  defaultValue: number;
  value: number;
  overridden: boolean;
  revision: number;
  reason: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
  updatedByTenant: string | null;
}

export interface SystemSettingsResponse {
  editable: boolean;
  editableReason: string | null;
  items: SystemSettingView[];
}

export interface UpdateSystemSettingRequest {
  value: number;
  reason: string;
  expectedRevision: number;
}

export interface ResetSystemSettingRequest {
  reason: string;
  expectedRevision: number;
}

export interface SystemConfigItem {
  group: string;
  key: string;
  envVar: string;
  label: string;
  value: string | null;
  configured: boolean;
  secret: boolean;
}

export interface SystemConfigurationResponse {
  activeProfiles: string[];
  persistenceMode: string;
  settingsEditorTenants: string[];
  settingsEditable: boolean;
  items: SystemConfigItem[];
  generatedAt: string;
}

export interface StaffPrincipal {
  principalId: string;
  tenantId: string;
  issuer: string;
  subject: string;
  role: string;
  displayName: string;
  status: 'ACTIVE' | 'DISABLED';
  evidenceRef: string;
  createdAt: string;
  createdBy: string;
  disabledAt: string | null;
  disabledBy: string | null;
  disabledReason: string | null;
}

export interface CreateStaffPrincipalRequest {
  issuer: string;
  subject: string;
  role: 'ADMIN';
  displayName: string;
  evidenceRef: string;
}
