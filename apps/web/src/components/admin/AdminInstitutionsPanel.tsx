import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Building2, RefreshCw } from 'lucide-react';
import { useApp } from '../../context/AppContext';
import type { Doctor } from '../../types/api';
import type { MedicalInstitution } from '../../types/admin';
import { isSyntheticDoctor } from '../../utils/realData';

export function AdminInstitutionsPanel() {
  const { client } = useApp();
  const [institutions, setInstitutions] = useState<MedicalInstitution[]>([]);
  const [doctors, setDoctors] = useState<Doctor[]>([]);
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setError('');
    try {
      const [registered, physicians] = await Promise.all([
        client.getMedicalInstitutions(), client.getAdminDoctors(),
      ]);
      setInstitutions(registered);
      setDoctors(physicians.items.filter(d => !isSyntheticDoctor(d)));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '의료기관 정보를 불러오지 못했습니다.');
    }
  }, [client]);

  useEffect(() => { void load(); }, [load]);

  async function create(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError('');
    try {
      await client.createMedicalInstitution(code.trim(), name.trim());
      setCode(''); setName('');
      await load();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '의료기관 등록에 실패했습니다.');
    } finally { setBusy(false); }
  }

  async function changeAssignment(institutionId: string, doctorId: string, assign: boolean) {
    setBusy(true); setError('');
    try {
      if (assign) await client.assignMedicalInstitutionDoctor(institutionId, doctorId);
      else await client.unassignMedicalInstitutionDoctor(institutionId, doctorId);
      await load();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '의사 배정을 변경하지 못했습니다.');
    } finally { setBusy(false); }
  }

  return <div className="admin-panel" data-testid="admin-institutions">
    <div className="admin-panel-header">
      <div><h2>의료기관 등록·판독의 배정</h2>
        <p>관리자가 실제 기관명과 기관 코드를 입력한 뒤 등록된 의사를 배정합니다. 입력한 기관명은 외부 기관 등록 여부를 자동 증명하지 않습니다.</p>
      </div>
      <button type="button" className="btn btn-outline" onClick={() => void load()} disabled={busy}>
        <RefreshCw size={16} /> 새로고침
      </button>
    </div>
    {error && <p role="alert" className="alert alert-error">{error}</p>}
    <form onSubmit={create} className="card" style={{ padding: 20, display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'end' }}>
      <label>기관 코드 (PACS 코드 또는 요양기관기호)<br />
        <input required minLength={2} maxLength={40} pattern="[A-Za-z0-9_-]+" value={code}
          onChange={e => setCode(e.target.value)} placeholder="기관 코드" /></label>
      <label>의료기관명<br />
        <input required minLength={2} maxLength={100} value={name}
          onChange={e => setName(e.target.value)} placeholder="정식 의료기관명" /></label>
      <button type="submit" className="btn btn-primary" disabled={busy}><Building2 size={16} /> 기관 등록</button>
    </form>
    {institutions.length === 0 && <p className="alert alert-info">등록된 의료기관이 없습니다. 위에서 실제 기관 정보를 입력해 주세요.</p>}
    {institutions.map(institution => {
      const available = doctors.filter(d => !institution.doctorIds.includes(d.id));
      return <section className="card" key={institution.id} style={{ marginTop: 16, padding: 20 }}>
        <h3>{institution.name} <small>({institution.code})</small></h3>
        <p>배정 의사 {institution.doctorIds.length}명</p>
        <ul>{institution.doctorIds.map(id => {
          const doctor = doctors.find(d => d.id === id);
          return <li key={id}>{doctor ? `${doctor.name} · 면허 ${doctor.licenseNumber}` : '현재 목록에서 확인할 수 없는 의사'}
            <button type="button" className="btn btn-outline" style={{ marginLeft: 12 }} disabled={busy}
              onClick={() => void changeAssignment(institution.id, id, false)}>배정 해제</button></li>;
        })}</ul>
        {available.length > 0 && <label>의사 배정<br />
          <select aria-label={`${institution.name} 의사 배정`} value="" disabled={busy}
            onChange={e => { if (e.target.value) void changeAssignment(institution.id, e.target.value, true); }}>
            <option value="">의사 선택</option>
            {available.map(d => <option key={d.id} value={d.id}>{d.name} · 면허 {d.licenseNumber}</option>)}
          </select></label>}
      </section>;
    })}
  </div>;
}
