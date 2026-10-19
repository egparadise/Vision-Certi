package com.inviz.visioncerti.certificate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.cert.CRL;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Trust anchors from {@code VISIONCERTI_CERT_TRUST_ANCHORS_FILE} (PEM bundle) and CRLs from
 * {@code VISIONCERTI_CERT_CRL_FILES} (comma-separated DER or PEM). Files are read on every call; an unreadable file
 * contributes nothing, which makes affected checks fail closed (untrusted / revocation UNKNOWN).
 */
public final class FileCertificateTrustSource implements CertificateTrustSource {

    private final Path anchorsFile;
    private final List<Path> crlFiles;

    public FileCertificateTrustSource(String anchorsFile, String crlFiles) {
        this.anchorsFile = anchorsFile == null || anchorsFile.isBlank() ? null : Path.of(anchorsFile.trim());
        this.crlFiles = crlFiles == null || crlFiles.isBlank() ? List.of()
                : Arrays.stream(crlFiles.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Path::of).toList();
    }

    @Override
    public List<X509Certificate> trustAnchors() {
        if (anchorsFile == null) {
            return List.of();
        }
        try {
            byte[] bytes = Files.readAllBytes(anchorsFile);
            List<X509Certificate> anchors = new ArrayList<>();
            for (Certificate c : CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(bytes))) {
                X509Certificate x = (X509Certificate) c;
                if (x.getBasicConstraints() >= 0) {
                    anchors.add(x);
                }
            }
            return anchors;
        } catch (IOException | GeneralSecurityException e) {
            return List.of();
        }
    }

    @Override
    public List<X509CRL> crls() {
        List<X509CRL> crls = new ArrayList<>();
        for (Path file : crlFiles) {
            try {
                byte[] bytes = Files.readAllBytes(file);
                for (CRL crl : CertificateFactory.getInstance("X.509").generateCRLs(new ByteArrayInputStream(bytes))) {
                    crls.add((X509CRL) crl);
                }
            } catch (IOException | GeneralSecurityException e) {
                // Missing or corrupt CRL file: affected certificates get revocation status UNKNOWN.
            }
        }
        return crls;
    }
}
