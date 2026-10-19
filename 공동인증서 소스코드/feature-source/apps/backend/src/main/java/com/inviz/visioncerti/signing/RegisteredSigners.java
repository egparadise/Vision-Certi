package com.inviz.visioncerti.signing;

/**
 * Physicians registered through admin preregistration + activation (not dev header identities). They are eligible
 * as document signers only while ACTIVE in the tenant, and they approve only through their own bearer session.
 */
public interface RegisteredSigners {

    RegisteredSigners NONE = new RegisteredSigners() {
        @Override
        public boolean isActiveSigner(String tenantId, String signerId) {
            return false;
        }

        @Override
        public String signatureMode() {
            return "NOT_CONFIGURED";
        }
    };

    boolean isActiveSigner(String tenantId, String signerId);

    /** Label shown on sign requests bound to a registered physician before a signature exists. */
    String signatureMode();
}
