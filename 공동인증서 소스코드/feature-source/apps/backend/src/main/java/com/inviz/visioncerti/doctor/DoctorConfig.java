package com.inviz.visioncerti.doctor;

import com.inviz.visioncerti.doctor.identity.GoogleIdTokenVerifier;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DoctorConfig {

    @Bean
    GoogleIdTokenVerifier googleIdTokenVerifier(Clock clock,
            @Value("${visioncerti.doctor.google.client-id:}") String clientId,
            @Value("${visioncerti.doctor.google.jwks-uri:" + GoogleIdTokenVerifier.DEFAULT_JWKS_URI + "}") String jwksUri) {
        return GoogleIdTokenVerifier.remote(clientId, jwksUri, clock);
    }

    @Bean
    DoctorPolicy doctorPolicy() {
        return DoctorPolicy.defaults();
    }
}
