package com.inviz.visioncerti.certificate;

import com.inviz.visioncerti.audit.AuditService;
import com.inviz.visioncerti.doctor.DoctorService;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires trust material (files, plus the dev mock CA when enabled), the mobile provider and the enrollment service. */
@Configuration
public class CertificateConfig {

    @Bean
    CertificateValidator certificateValidator(Clock clock,
            @Value("${visioncerti.cert.trust-anchors-file:}") String anchorsFile,
            @Value("${visioncerti.cert.crl-files:}") String crlFiles,
            ObjectProvider<DevMockCertificateAuthority> mockCa) {
        List<CertificateTrustSource> sources = new ArrayList<>();
        sources.add(new FileCertificateTrustSource(anchorsFile, crlFiles));
        DevMockCertificateAuthority mock = mockCa.getIfAvailable();
        if (mock != null) {
            sources.add(mock);
        }
        return new CertificateValidator(CertificateTrustSource.composite(sources), clock);
    }

    /** No mobile adapter is bundled: whatever VISIONCERTI_MOBILE_PROVIDER says, mobile reports "not configured". */
    @Bean
    MobileIdentityProvider mobileIdentityProvider(@Value("${visioncerti.mobile.provider:}") String provider) {
        return new MobileIdentityProvider.NotConfigured(provider);
    }

    @Bean
    CertificateEnrollmentService certificateEnrollmentService(DoctorService doctors, CertificateValidator validator,
            MobileIdentityProvider mobile, ObjectProvider<DevMockCertificateAuthority> mockCa, AuditService audit,
            Clock clock, @Value("${visioncerti.cert.service-id:vision-certi-dev}") String serviceId,
            @Value("${visioncerti.cert.challenge-ttl:PT5M}") Duration challengeTtl) {
        return new CertificateEnrollmentService(doctors, validator, mobile, mockCa.getIfAvailable(), audit, clock,
                serviceId, challengeTtl);
    }
}
