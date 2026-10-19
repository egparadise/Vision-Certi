package com.inviz.visioncerti.signing;

import com.inviz.visioncerti.certificate.CertificateValidator;
import com.inviz.visioncerti.common.ApiException;
import com.inviz.visioncerti.common.Hashing;
import com.inviz.visioncerti.document.Document;
import com.inviz.visioncerti.signing.SigningDtos.ApproveSignRequest;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Base64;

/** Exact, length-prefixed bytes for one doctor's approval of one immutable report version. */
final class ReportCertificateApproval {
    private ReportCertificateApproval() {}

    static byte[] payload(SignRequest request, Document document) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(out);
            for (String field : new String[] {"vision-certi-pacs-report-approval-v1", request.tenantId(),
                    request.id().toString(), request.documentId().toString(), request.documentVersionId().toString(),
                    document.externalDocumentId() == null ? "" : document.externalDocumentId(),
                    document.externalStudyId() == null ? "" : document.externalStudyId(),
                    request.documentSha256(), request.signerId(), request.nonce(),
                    ApproveSignRequest.REVIEWED_EXACT_REPORT_CONTENT}) {
                byte[] value = field.getBytes(StandardCharsets.UTF_8);
                data.writeInt(value.length);
                data.write(value);
            }
            data.flush();
            return out.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static X509Certificate verify(SignRequest request, Document document, ApproveSignRequest body,
            String expectedFingerprint) {
        if (expectedFingerprint == null || body.certificateDerBase64() == null
                || body.externalSignatureBase64() == null || body.externalSignatureAlgorithm() == null) {
            throw ApiException.unprocessable("REPORT_CERTIFICATE_SIGNATURE_REQUIRED",
                    "판독 확정에는 등록한 공동인증서의 직접 서명이 필요합니다.");
        }
        try {
            byte[] certDer = Base64.getDecoder().decode(body.certificateDerBase64());
            byte[] signed = Base64.getDecoder().decode(body.externalSignatureBase64());
            if (certDer.length > 65536 || signed.length > 16384) throw new IllegalArgumentException();
            X509Certificate cert = CertificateValidator.parseDer(certDer);
            if (!Hashing.constantTimeEquals(CertificateValidator.fingerprint(cert), expectedFingerprint)) {
                throw ApiException.forbidden("REPORT_CERTIFICATE_MISMATCH", "등록한 공동인증서와 다릅니다.");
            }
            if (!verifyRaw(cert, body.externalSignatureAlgorithm(), payload(request, document), signed)) {
                throw ApiException.unprocessable("REPORT_CERTIFICATE_SIGNATURE_INVALID",
                        "판독문 버전과 일치하는 공동인증서 서명이 아닙니다.");
            }
            return cert;
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("REPORT_CERTIFICATE_PROOF_INVALID", "공동인증서 서명 형식이 올바르지 않습니다.");
        }
    }

    static boolean verifyRaw(X509Certificate cert, String algorithm, byte[] payload, byte[] value) {
        PublicKey key = cert.getPublicKey();
        if (!("SHA256withRSA".equals(algorithm) && "RSA".equals(key.getAlgorithm())
                || "SHA256withECDSA".equals(algorithm) && "EC".equals(key.getAlgorithm()))) return false;
        try {
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(key);
            verifier.update(payload);
            return verifier.verify(value);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
