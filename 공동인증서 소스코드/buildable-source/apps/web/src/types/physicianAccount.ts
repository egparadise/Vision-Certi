import type { Doctor } from './api';
export interface PhysicianAccountConfig { available: boolean; consentVersion: string; scope: 'ENROLLMENT_ONLY' }
export interface PhysicianAccountRegistration {
  username: string; password: string; name: string; phone: string; licenseNumber: string; centerName: string;
  privacyConsent: boolean; consentVersion: string;
}
export interface PhysicianAccountView {
  accountId: string; username: string; doctor: Doctor; phoneVerified: boolean; scope: 'ENROLLMENT_ONLY';
}
export interface PhysicianAccountReceipt { enrollmentToken: string; expiresAt: string; account: PhysicianAccountView }
