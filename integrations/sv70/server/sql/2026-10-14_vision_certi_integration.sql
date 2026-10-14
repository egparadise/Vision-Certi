-- SV70 PACS main database, MySQL 8.0.16+ / MariaDB 10.6+.
-- Apply with a schema owner BEFORE enabling VISION_CERTI_INTEGRATION_ENABLED.
-- No existing PACS tables or production data are changed by this migration.
-- Use UTC connections and utf8mb4; run once using a migration ledger.
SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE pacs_vc_doctor_link (
 link_idx INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_idx INT NOT NULL,
 vc_tenant_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 vc_doctor_id CHAR(36) COLLATE utf8mb4_bin NOT NULL,
 status ENUM('ACTIVE','REVOKED') NOT NULL,
 active_user_idx INT NULL UNIQUE,
 active_doctor_key VARCHAR(101) COLLATE utf8mb4_bin NULL UNIQUE,
 evidence_ref VARCHAR(255) NOT NULL,
 note VARCHAR(500) NULL,
 created_by_user_idx INT NOT NULL,
 created_at DATETIME(6) NOT NULL,
 revoked_by_user_idx INT NULL,
 revoked_at DATETIME(6) NULL,
 revoke_reason VARCHAR(255) NULL,
 KEY ix_pacs_vc_doctor_link_doctor(vc_tenant_id,vc_doctor_id),
 KEY ix_pacs_vc_doctor_link_user(user_idx),
 CONSTRAINT ck_vc_link_active CHECK (
  (status='ACTIVE' AND active_user_idx IS NOT NULL AND active_user_idx=user_idx
   AND active_doctor_key IS NOT NULL AND active_doctor_key=CONCAT(vc_tenant_id,':',vc_doctor_id)
   AND revoked_at IS NULL)
  OR (status='REVOKED' AND active_user_idx IS NULL AND active_doctor_key IS NULL
   AND revoked_at IS NOT NULL AND revoked_by_user_idx IS NOT NULL AND revoke_reason IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE pacs_vc_session (
 session_idx INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 token_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 user_idx INT NOT NULL,
 link_idx INT NOT NULL,
 vc_tenant_id VARCHAR(64) NOT NULL,
 vc_doctor_id CHAR(36) NOT NULL,
 vc_enrollment_id CHAR(36) NOT NULL,
 vc_certificate_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 vc_identity_mode VARCHAR(32) NOT NULL,
 vc_authenticated_at DATETIME(6) NOT NULL,
 vc_session_expires_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 expires_at DATETIME(6) NOT NULL,
 revoked_at DATETIME(6) NULL,
 revoke_reason VARCHAR(64) NULL,
 client_ip VARCHAR(64) NULL,
 KEY ix_pacs_vc_session_user(user_idx),
 KEY ix_pacs_vc_session_expires(expires_at),
 CONSTRAINT fk_vc_session_link FOREIGN KEY(link_idx) REFERENCES pacs_vc_doctor_link(link_idx),
 CONSTRAINT ck_vc_session_expiry CHECK(expires_at>created_at AND expires_at<=vc_session_expires_at
  AND expires_at<=DATE_ADD(created_at,INTERVAL 60 MINUTE)),
 CONSTRAINT ck_vc_session_hash CHECK(token_sha256 REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE pacs_vc_report_approval (
 approval_idx INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 approval_id CHAR(36) NOT NULL UNIQUE,
 study_idx INT NOT NULL,
 report_idx INT NOT NULL,
 revision INT NOT NULL,
 target_status ENUM('A','RA') NOT NULL,
 user_idx INT NOT NULL,
 link_idx INT NOT NULL,
 session_idx INT NOT NULL,
 vc_tenant_id VARCHAR(64) NOT NULL,
 vc_doctor_id CHAR(36) NOT NULL,
 content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 snapshot_json LONGTEXT NOT NULL,
 snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(32) NOT NULL,
 state_reason VARCHAR(255) NULL,
 attempt_count INT NOT NULL DEFAULT 0,
 last_error_code VARCHAR(64) NULL,
 vc_sign_request_id CHAR(36) NULL,
 vc_document_version_id CHAR(36) NULL,
 vc_request_expires_at DATETIME(6) NULL,
 vc_signature_id CHAR(36) NULL,
 vc_signature_algorithm VARCHAR(64) NULL,
 vc_signature_mode VARCHAR(64) NULL,
 vc_signature_value TEXT NULL,
 vc_signed_at DATETIME(6) NULL,
 vc_certificate_enrollment_id CHAR(36) NULL,
 vc_certificate_fingerprint CHAR(64) NULL,
 vc_certificate_serial VARCHAR(128) NULL,
 vc_key_name VARCHAR(255) NULL,
 vc_key_version INT NULL,
 vc_public_key_spki TEXT NULL,
 vc_evidence_json LONGTEXT NULL,
 supersedes_approval_id CHAR(36) NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 committed_at DATETIME(6) NULL,
 UNIQUE KEY uq_pacs_vc_report_approval_revision(report_idx,revision),
 KEY ix_pacs_vc_report_approval_study(study_idx),
 KEY ix_pacs_vc_report_approval_state(state),
 CONSTRAINT fk_vc_approval_link FOREIGN KEY(link_idx) REFERENCES pacs_vc_doctor_link(link_idx),
 CONSTRAINT fk_vc_approval_session FOREIGN KEY(session_idx) REFERENCES pacs_vc_session(session_idx),
 CONSTRAINT ck_vc_approval_revision CHECK(revision>0 AND attempt_count>=0),
 CONSTRAINT ck_vc_approval_state CHECK(state IN ('PREPARING','PREPARE_RETRY','PENDING_SIGNATURE',
  'SIGNED_REMOTE','APPROVED','EXPIRED','REMOTE_FAILED','SUPERSEDED','SUPERSEDED_AFTER_SIGNATURE','COMMIT_REFUSED')),
 CONSTRAINT ck_vc_approval_snapshot CHECK(JSON_VALID(snapshot_json) AND snapshot_sha256=CONVERT(SHA2(snapshot_json,256) USING ascii) COLLATE ascii_bin),
 CONSTRAINT ck_vc_approval_evidence CHECK(state NOT IN ('SIGNED_REMOTE','APPROVED','SUPERSEDED_AFTER_SIGNATURE') OR
  (vc_signature_id IS NOT NULL AND vc_signature_value IS NOT NULL AND vc_signed_at IS NOT NULL
   AND vc_evidence_json IS NOT NULL AND vc_sign_request_id IS NOT NULL)),
 CONSTRAINT ck_vc_approval_commit CHECK(state<>'APPROVED' OR committed_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE pacs_vc_audit (
 audit_idx BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 at DATETIME(6) NOT NULL,
 actor_user_idx INT NULL,
 action VARCHAR(64) NOT NULL,
 target_type VARCHAR(32) NOT NULL,
 target_id VARCHAR(64) NULL,
 outcome VARCHAR(16) NOT NULL,
 detail_json TEXT NULL,
 KEY ix_pacs_vc_audit_target(target_type,target_id,at),
 CONSTRAINT ck_vc_audit_outcome CHECK(outcome IN ('SUCCESS','DENIED','ERROR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Write-once identity/snapshot/evidence is also enforced inside the database.
DELIMITER $$
CREATE TRIGGER vc_link_no_delete BEFORE DELETE ON pacs_vc_doctor_link FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VC_LINK_APPEND_ONLY'; END$$
CREATE TRIGGER vc_link_identity_immutable BEFORE UPDATE ON pacs_vc_doctor_link FOR EACH ROW
BEGIN
 IF NOT (NEW.user_idx<=>OLD.user_idx) OR NOT (NEW.vc_tenant_id<=>OLD.vc_tenant_id)
 OR NOT (NEW.vc_doctor_id<=>OLD.vc_doctor_id) OR NOT (NEW.evidence_ref<=>OLD.evidence_ref)
 OR NOT (NEW.created_at<=>OLD.created_at) OR NOT (NEW.created_by_user_idx<=>OLD.created_by_user_idx)
 OR (OLD.status='REVOKED' AND NEW.status<>'REVOKED') THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VC_LINK_IDENTITY_IMMUTABLE'; END IF;
END$$
CREATE TRIGGER vc_approval_no_delete BEFORE DELETE ON pacs_vc_report_approval FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VC_APPROVAL_APPEND_ONLY'; END$$
CREATE TRIGGER vc_approval_immutable BEFORE UPDATE ON pacs_vc_report_approval FOR EACH ROW
BEGIN
 IF NOT (NEW.approval_id<=>OLD.approval_id) OR NOT (NEW.study_idx<=>OLD.study_idx)
 OR NOT (NEW.report_idx<=>OLD.report_idx) OR NOT (NEW.revision<=>OLD.revision)
 OR NOT (NEW.target_status<=>OLD.target_status) OR NOT (NEW.user_idx<=>OLD.user_idx)
 OR NOT (NEW.link_idx<=>OLD.link_idx) OR NOT (NEW.session_idx<=>OLD.session_idx)
 OR NOT (NEW.vc_tenant_id<=>OLD.vc_tenant_id) OR NOT (NEW.vc_doctor_id<=>OLD.vc_doctor_id)
 OR NOT (NEW.content_sha256<=>OLD.content_sha256) OR NOT (NEW.snapshot_json<=>OLD.snapshot_json)
 OR NOT (NEW.snapshot_sha256<=>OLD.snapshot_sha256) OR NOT (NEW.created_at<=>OLD.created_at)
 OR NOT (NEW.supersedes_approval_id<=>OLD.supersedes_approval_id) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VC_SNAPSHOT_IMMUTABLE'; END IF;
 IF OLD.vc_sign_request_id IS NOT NULL AND (NOT (NEW.vc_sign_request_id<=>OLD.vc_sign_request_id)
 OR NOT (NEW.vc_document_version_id<=>OLD.vc_document_version_id)) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VC_REQUEST_BINDING_IMMUTABLE'; END IF;
 IF OLD.vc_signature_id IS NOT NULL AND (
 NOT (NEW.vc_signature_id<=>OLD.vc_signature_id) OR NOT (NEW.vc_signature_algorithm<=>OLD.vc_signature_algorithm)
 OR NOT (NEW.vc_signature_mode<=>OLD.vc_signature_mode) OR NOT (NEW.vc_signature_value<=>OLD.vc_signature_value)
 OR NOT (NEW.vc_signed_at<=>OLD.vc_signed_at) OR NOT (NEW.vc_evidence_json<=>OLD.vc_evidence_json)
 OR NOT (NEW.vc_certificate_enrollment_id<=>OLD.vc_certificate_enrollment_id)
 OR NOT (NEW.vc_certificate_fingerprint<=>OLD.vc_certificate_fingerprint)
 OR NOT (NEW.vc_certificate_serial<=>OLD.vc_certificate_serial) OR NOT (NEW.vc_key_name<=>OLD.vc_key_name)
 OR NOT (NEW.vc_key_version<=>OLD.vc_key_version) OR NOT (NEW.vc_public_key_spki<=>OLD.vc_public_key_spki)) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VC_SIGNATURE_IMMUTABLE'; END IF;
 IF OLD.committed_at IS NOT NULL AND NOT (NEW.committed_at<=>OLD.committed_at) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VC_COMMIT_IMMUTABLE'; END IF;
END$$
CREATE TRIGGER vc_audit_no_update BEFORE UPDATE ON pacs_vc_audit FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VC_AUDIT_APPEND_ONLY'; END$$
CREATE TRIGGER vc_audit_no_delete BEFORE DELETE ON pacs_vc_audit FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='VC_AUDIT_APPEND_ONLY'; END$$
DELIMITER ;
