import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MedicalCredentialsForm } from '../components/MedicalCredentialsForm';
import type { Doctor, Enrollment } from '../types/api';

const doctor = { name: '시험 의사', centerName: '시험 의료원', licenseNumber: '123456' } as Doctor;
const pending = { registrationId: 'registration', medicalCredentialsRequired: true, reviewStatus: 'PENDING' } as Enrollment;

describe('Medical credentials submission', () => {
  it('requires specialist number and specialty and sends both without claiming verification', async () => {
    const save = vi.fn().mockResolvedValue(undefined);
    render(<MedicalCredentialsForm doctor={doctor} enrollment={pending} onSave={save} />);
    expect(screen.getByLabelText(/의사면허번호/)).toHaveValue('123456');
    fireEvent.click(screen.getByLabelText('전문의'));
    const form = screen.getByRole('button', { name: '면허·자격 정보 제출' }).closest('form')!;
    fireEvent.submit(form);
    expect(save).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toHaveTextContent('자격번호와 전문과목');
    fireEvent.change(screen.getByLabelText(/전문의 자격번호/), { target: { value: 'SP-567' } });
    fireEvent.change(screen.getByLabelText(/전문과목/), { target: { value: '영상의학과' } });
    fireEvent.submit(form);
    await waitFor(() => expect(save).toHaveBeenCalledWith({ medicalLicenseNumber: '123456', specialist: true,
      specialistQualificationNumber: 'SP-567', specialty: '영상의학과' }));
    expect(screen.queryByText('면허·자격 검토 완료')).not.toBeInTheDocument();
  });

  it('does not send stale specialist fields when the doctor has no specialist qualification', async () => {
    const save = vi.fn().mockResolvedValue(undefined);
    render(<MedicalCredentialsForm doctor={doctor} enrollment={pending} onSave={save} />);
    fireEvent.click(screen.getByLabelText('전문의'));
    fireEvent.change(screen.getByLabelText(/전문의 자격번호/), { target: { value: '999' } });
    fireEvent.click(screen.getByLabelText('전문의 자격 없음'));
    fireEvent.click(screen.getByRole('button', { name: '면허·자격 정보 제출' }));
    await waitFor(() => expect(save).toHaveBeenCalledWith({ medicalLicenseNumber: '123456', specialist: false,
      specialistQualificationNumber: null, specialty: null }));
  });

  it('shows rejected submissions without marking them saved and labels accepted submissions pending review', async () => {
    const save = vi.fn().mockRejectedValue(new Error('PACS 면허번호와 일치하지 않습니다.'));
    const { rerender } = render(<MedicalCredentialsForm doctor={doctor} enrollment={pending} onSave={save} />);
    fireEvent.click(screen.getByLabelText('전문의 자격 없음'));
    fireEvent.click(screen.getByRole('button', { name: '면허·자격 정보 제출' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('일치하지 않습니다');
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    rerender(<MedicalCredentialsForm doctor={doctor} enrollment={{ ...pending, specialist: false,
      medicalLicenseNumber: '123456', medicalCredentialsSubmittedAt: new Date().toISOString() }} onSave={save} />);
    expect(screen.getByRole('status')).toHaveTextContent('검토 대기');
    expect(screen.queryByText('면허·자격 검토 완료')).not.toBeInTheDocument();
  });
});
