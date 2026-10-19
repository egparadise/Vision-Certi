import type { Doctor } from '../types/api';

/** Historical local E2E fixtures remain in the audit store but must never appear as operating physicians. */
export function isSyntheticDoctor(doctor: Pick<Doctor, 'name' | 'centerName'>): boolean {
  return /^(E2E\b|Synthetic\b)/i.test(doctor.name.trim())
    || /^Synthetic\b/i.test((doctor.centerName || '').trim());
}
