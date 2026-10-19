package com.inviz.visioncerti.signing;

import com.inviz.visioncerti.common.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Outside dev there is no legacy (non-physician) signer: the dev mock HMAC signer does not exist and no other
 * implementation is provided. Signatures are made only by registered physicians with their OpenBao keys.
 */
@Component
@Profile("!dev")
public class UnavailableSigningProvider implements SigningProvider {

    public static final String MODE = "LEGACY_SIGNING_DISABLED";

    @Override
    public String signatureMode() {
        return MODE;
    }

    @Override
    public String algorithm() {
        return "NONE";
    }

    @Override
    public boolean clinicalUseAllowed() {
        return false;
    }

    @Override
    public String sign(byte[] payload) {
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "LEGACY_SIGNING_DISABLED",
                "Only registered physicians can sign; legacy signing is disabled");
    }

    @Override
    public Check verify(byte[] payload, String signatureValue) {
        return Check.UNVERIFIABLE;
    }
}
