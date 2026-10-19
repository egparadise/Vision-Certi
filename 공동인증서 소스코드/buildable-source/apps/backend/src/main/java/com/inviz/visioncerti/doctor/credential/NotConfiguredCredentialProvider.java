package com.inviz.visioncerti.doctor.credential;

/** Default: no key service. Activation fails closed and physicians stay CONSENTED. */
public final class NotConfiguredCredentialProvider implements DoctorCredentialProvider {

    private final String backend;
    private final String reason;

    public NotConfiguredCredentialProvider(String backend, String reason) {
        this.backend = backend;
        this.reason = reason;
    }

    @Override
    public String backend() {
        return backend;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String unavailableReason() {
        return reason;
    }

    @Override
    public String signatureMode() {
        return "NOT_CONFIGURED";
    }

    @Override
    public boolean cryptographicallyAttributable() {
        return false;
    }

    @Override
    public boolean checksRevocationRemotely() {
        return false;
    }

    @Override
    public DoctorCredential provision(String doctorId) {
        throw new CredentialException(CredentialException.Kind.NOT_CONFIGURED, reason);
    }

    @Override
    public byte[] signDigest(DoctorCredential credential, byte[] sha256Digest) {
        throw new CredentialException(CredentialException.Kind.NOT_CONFIGURED, reason);
    }

    @Override
    public void assertNotRevoked(DoctorCredential credential) {
        throw new CredentialException(CredentialException.Kind.NOT_CONFIGURED, reason);
    }

    @Override
    public void revoke(DoctorCredential credential) {
        // Nothing was issued.
    }
}
