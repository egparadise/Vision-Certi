import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { RegistrationView } from '../components/RegistrationView';

describe('RegistrationView Account Type Distinction Tests', () => {
  it('explains doctor invitation requirement and links to #/activate and #/doctors when clinician type is selected', () => {
    render(<RegistrationView />);

    // Click on clinician / doctor radio option
    const clinicianRadio = screen.getByLabelText(/의료인/);
    fireEvent.click(clinicianRadio);

    // Verify explanation banner and links
    const notice = screen.getByTestId('clinician-activation-notice');
    expect(notice).toBeInTheDocument();
    expect(notice).toHaveTextContent('의사 계정은 병원 관리자의 사전 등록');

    const activateLink = screen.getByTestId('link-to-doctor-activation');
    expect(activateLink).toHaveAttribute('href', '#/activate');

    const adminLink = screen.getByTestId('link-to-doctor-admin');
    expect(adminLink).toHaveAttribute('href', '#/doctors');
  });

  it('preserves personal signup flow and allows progressing to Step 1', () => {
    render(<RegistrationView />);

    // Select personal account
    const personalRadio = screen.getByLabelText(/개인/);
    fireEvent.click(personalRadio);

    // Next step button
    const nextBtn = screen.getByTestId('goto-personal-consent-btn');
    expect(nextBtn).toBeInTheDocument();
    fireEvent.click(nextBtn);

    // Verify progression to step 1
    expect(screen.getByRole('heading', { name: /서비스 가입 동의/ })).toBeInTheDocument();
  });
});
