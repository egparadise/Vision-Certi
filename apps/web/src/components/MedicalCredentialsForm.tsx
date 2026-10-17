import { useState } from 'react';
import type { Doctor, Enrollment, MedicalCredentialsRequest } from '../types/api';
import './medical-credentials.css';

export function MedicalCredentialsForm({ doctor, enrollment, onSave }: {
  doctor: Doctor | null;
  enrollment: Enrollment;
  onSave: (body: MedicalCredentialsRequest) => Promise<void>;
}) {
  const [license, setLicense] = useState(enrollment.medicalLicenseNumber || doctor?.licenseNumber || '');
  const [kind, setKind] = useState(enrollment.specialist == null ? '' : enrollment.specialist ? 'specialist' : 'general');
  const [qualification, setQualification] = useState(enrollment.specialistQualificationNumber || '');
  const [specialty, setSpecialty] = useState(enrollment.specialty || '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const submitted = Boolean(enrollment.medicalCredentialsSubmittedAt);

  return <section className="medical-credentials" data-testid="medical-credentials-form">
    <h3>의료인 자격 정보</h3>
    <p>{doctor?.name} · {doctor?.centerName}</p>
    {submitted ? <div role="status" className="medical-credentials-receipt">
      <strong>{enrollment.reviewStatus === 'APPROVED' ? '면허·자격 검토 완료' : '면허·자격 정보 제출 완료 · 검토 대기'}</strong>
      <dl><dt>의사면허번호</dt><dd>{enrollment.medicalLicenseNumber}</dd>
        <dt>전문의 자격</dt><dd>{enrollment.specialist ? `${enrollment.specialty} / ${enrollment.specialistQualificationNumber}` : '해당 없음'}</dd></dl>
      <p>아래 이용 동의를 완료해주세요. 인증서와 의료인 정보의 연결 검토가 끝나면 등록을 완료할 수 있습니다.</p>
    </div> : <form onSubmit={async event => {
      event.preventDefault();
      if (busy) return;
      setError('');
      if (!/^\d{1,20}$/.test(license.trim()) || !kind || (kind === 'specialist' && (!qualification.trim() || qualification.trim().length > 50 || !specialty.trim()))) {
        setError('의사면허번호와 전문의 자격 여부를 확인해주세요. 전문의는 자격번호와 전문과목을 모두 입력해주세요.');
        return;
      }
      setBusy(true);
      try {
        await onSave({ medicalLicenseNumber: license.trim(), specialist: kind === 'specialist',
          specialistQualificationNumber: kind === 'specialist' ? qualification.trim() : null,
          specialty: kind === 'specialist' ? specialty.trim() : null });
      } catch (reason) { setError(reason instanceof Error ? reason.message : '면허 정보를 저장하지 못했습니다. 다시 시도해주세요.'); }
      finally { setBusy(false); }
    }}>
      <label htmlFor="medical-license">의사면허번호 <span>(필수)</span></label>
      <input id="medical-license" inputMode="numeric" pattern="[0-9]{1,20}" maxLength={20} required value={license} onChange={e => setLicense(e.target.value)} autoComplete="off" />
      <p className="medical-field-help">PACS에 등록된 본인의 면허번호를 확인해주세요.</p>
      <fieldset><legend>전문의 자격 여부 (필수)</legend>
        <label><input type="radio" name="specialist" value="specialist" required checked={kind === 'specialist'} onChange={() => setKind('specialist')} /> 전문의</label>
        <label><input type="radio" name="specialist" value="general" required checked={kind === 'general'} onChange={() => setKind('general')} /> 전문의 자격 없음</label>
      </fieldset>
      {kind === 'specialist' && <div className="medical-specialist-fields">
        <div><label htmlFor="specialist-number">전문의 자격번호 <span>(필수)</span></label>
          <input id="specialist-number" maxLength={50} required value={qualification} onChange={e => setQualification(e.target.value)} autoComplete="off" /></div>
        <div><label htmlFor="medical-specialty">전문과목 <span>(필수)</span></label>
          <input id="medical-specialty" maxLength={100} required placeholder="예: 영상의학과" value={specialty} onChange={e => setSpecialty(e.target.value)} autoComplete="off" /></div>
      </div>}
      <p className="medical-field-help">면허·자격증에 적힌 정보를 입력해주세요. 기관 담당자가 증빙과 대조하여 확인합니다.</p>
      {error && <p role="alert">{error}</p>}
      <button type="submit" disabled={busy} className="btn btn-primary">{busy ? '저장 중…' : '면허·자격 정보 제출'}</button>
    </form>}
  </section>;
}
