import assert from 'node:assert/strict';
export async function verifyDoctorSchema(db) {
  const t='00000000-0000-0000-0000-000000000011', other='00000000-0000-0000-0000-000000000012';
  const admin='11000000-0000-0000-0000-000000000001', d='22000000-0000-0000-0000-000000000001';
  const invite='33000000-0000-0000-0000-000000000001', binding='44000000-0000-0000-0000-000000000001';
  const consent='55000000-0000-0000-0000-000000000001', key='66000000-0000-0000-0000-000000000001';
  const cert='77000000-0000-0000-0000-000000000001', external='88000000-0000-0000-0000-000000000001';
  const challenge='99000000-0000-0000-0000-000000000001', evidence='aa000000-0000-0000-0000-000000000001';
  const checks=[];
  async function deny(label,sql,code) {
    await db.exec('SAVEPOINT expected_rejection');
    let error;
    try { await db.exec(sql); } catch(e) { error=e; }
    await db.exec('ROLLBACK TO SAVEPOINT expected_rejection; RELEASE SAVEPOINT expected_rejection');
    assert.ok(error, `${label}: statement was accepted`);
    if(code) assert.equal(error.code,code,label);
    checks.push(label);
  }
  await db.exec('BEGIN');
  try {
    await db.exec(`INSERT INTO certi.tenants VALUES ('${t}','cert-a','Synthetic A','ACTIVE',now()),('${other}','cert-b','Synthetic B','ACTIVE',now());
      INSERT INTO certi.identities VALUES ('${admin}','urn:test','admin','Synthetic admin','ACTIVE',now());
      SET LOCAL ROLE certi_app; SET LOCAL app.tenant_id='${t}';
      INSERT INTO certi.doctor_profiles(id,tenant_id,full_name,license_number,center_name,invitation_email,created_by)
      VALUES ('${d}','${t}','Test physician','12345','Synthetic center','test@example.invalid','${admin}');`);
    await deny('no direct ACTIVE doctor',`UPDATE certi.doctor_profiles SET state='ACTIVE' WHERE id='${d}'`,'23514');
    await deny('identity needs independent review and proof',`UPDATE certi.doctor_profiles SET state='IDENTITY_VERIFIED' WHERE id='${d}'`,'23514');
    await deny('key without consent rejected',`INSERT INTO certi.doctor_keys VALUES('${key}','${t}','${d}','${consent}','OPENBAO_TRANSIT','test-key','ECDSA_P256_SHA256',1,'public',now())`,'23503');
    await db.exec(`INSERT INTO certi.doctor_reviews VALUES(gen_random_uuid(),'${t}','${d}','${admin}','review/identity','review/license',now(),'VERIFIED');
      INSERT INTO certi.doctor_invites(id,tenant_id,doctor_id,token_digest,expires_at) VALUES('${invite}','${t}','${d}',repeat('a',64),now()+interval '1 hour');
      INSERT INTO certi.doctor_bindings VALUES('${binding}','${t}','${d}','${invite}','urn:inviz:pc-ca:test','fingerprint','PC_CERT','proof/ref',now());
      UPDATE certi.doctor_profiles SET state='IDENTITY_VERIFIED' WHERE id='${d}';`);
    await deny('cannot consent without physician evidence',`UPDATE certi.doctor_profiles SET state='CONSENTED' WHERE id='${d}'`,'23514');
    await db.exec(`INSERT INTO certi.doctor_consents VALUES('${consent}','${t}','${d}','${binding}','2026-10-13-draft',repeat('b',64),true,true,true,now(),'consent/ref');
      UPDATE certi.doctor_profiles SET state='CONSENTED' WHERE id='${d}';`);
    await deny('ACTIVE requires valid internal certificate',`UPDATE certi.doctor_profiles SET state='ACTIVE' WHERE id='${d}'`,'23514');
    await db.exec(`INSERT INTO certi.doctor_keys VALUES('${key}','${t}','${d}','${consent}','OPENBAO_TRANSIT','test-key','ECDSA_P256_SHA256',1,'public',now());
      INSERT INTO certi.doctor_certificates(id,tenant_id,doctor_id,key_id,serial_number,certificate_pem,certificate_sha256,certificate_kind,issuer_ref,not_before,not_after)
      VALUES('${cert}','${t}','${d}','${key}','1','public cert',repeat('c',64),'INVIZ_PRIVATE_X509','test-ca',now()-interval '1 hour',now()+interval '1 day');
      `);
    await deny('internal signing certificate alone cannot activate doctor',`UPDATE certi.doctor_profiles SET state='ACTIVE' WHERE id='${d}'`,'23514');
    await deny('immutable consent evidence',`UPDATE certi.doctor_consents SET version='bad' WHERE id='${consent}'`,'42501');
    await deny('foreign tenant doctor certificate rejected',`INSERT INTO certi.doctor_auth_certificates(id,tenant_id,doctor_id,certificate_type,provider,provider_subject,expires_at) VALUES(gen_random_uuid(),'${other}','${d}','MOBILE_CERT','test','subject',now()+interval '1 day')`,'42501');
    await db.exec(`INSERT INTO certi.doctor_auth_certificates(id,tenant_id,doctor_id,certificate_type,provider,issuer,serial_number,certificate_fingerprint,public_certificate,expires_at)
      VALUES('${external}','${t}','${d}','PC_CERT','test','Test CA','123',repeat('d',64),'public only',now()+interval '1 day');`);
    await deny('external certificate cannot activate on unverified proof',`UPDATE certi.doctor_auth_certificates SET status='ACTIVE',external_status='GOOD' WHERE id='${external}'`,'23514');
    await db.exec(`INSERT INTO certi.certificate_challenges(id,tenant_id,doctor_id,purpose,service_id,nonce_digest,canonical_payload,payload_sha256,expires_at)
      VALUES('${challenge}','${t}','${d}','CERTIFICATE_ENROLLMENT','INVIZ',repeat('e',64),'payload',repeat('f',64),now()+interval '3 minutes');
      INSERT INTO certi.certificate_validation_evidence VALUES('${evidence}','${t}','${d}','${external}','${challenge}','proof/ref','SHA256withECDSA','VALID','TRUSTED','GOOD','CRL','crl/ref',now()-interval '1 minute',now()+interval '1 hour',now(),now(),'policy-v1');`);
    await deny('cryptographic proof is not physician identity review',`UPDATE certi.doctor_auth_certificates SET status='ACTIVE',external_status='GOOD' WHERE id='${external}'`,'23514');
    await db.exec(`INSERT INTO certi.certificate_binding_reviews VALUES(gen_random_uuid(),'${t}','${d}','${external}','${evidence}','${admin}','identity-review/ref','VERIFIED',now());
      UPDATE certi.doctor_auth_certificates SET status='ACTIVE',external_status='GOOD',last_verified_at=now() WHERE id='${external}';
      UPDATE certi.doctor_profiles SET state='ACTIVE' WHERE id='${d}';
      UPDATE certi.doctor_auth_certificates SET external_status='REVOKED' WHERE id='${external}';`);
    assert.equal((await db.query(`SELECT authority_version FROM certi.doctor_profiles WHERE id='${d}'`)).rows[0].authority_version,4);
    checks.push('ordered doctor lifecycle with both certificate tiers');
    const row=(await db.query(`SELECT status,external_status FROM certi.doctor_auth_certificates WHERE id='${external}'`)).rows[0];
    assert.deepEqual(row,{status:'ACTIVE',external_status:'REVOKED'});
    checks.push('external CA status independent of internal registration');
    await db.exec(`UPDATE certi.doctor_auth_certificates SET status='REVOKED',revoked_at=now(),revocation_reason='unregister' WHERE id='${external}'`);
    await deny('unregistered certificate cannot silently reactivate',`UPDATE certi.doctor_auth_certificates SET status='PENDING',revoked_at=NULL,revocation_reason=NULL WHERE id='${external}'`,'23514');
    await deny('unsigned mobile SUCCESS cannot be recorded VERIFIED',`INSERT INTO certi.mobile_auth_transactions(id,tenant_id,doctor_id,provider,purpose,nonce_digest,callback_config_id,state,expires_at) VALUES(gen_random_uuid(),'${t}','${d}','test','DOCTOR_ENROLLMENT',repeat('1',64),'pinned-callback','VERIFIED',now()+interval '5 minutes')`,'23514');
    await db.exec(`SET LOCAL app.tenant_id='${other}'`);
    assert.equal((await db.query('SELECT count(*)::int as count FROM certi.doctor_auth_certificates')).rows[0].count,0);
    checks.push('external certificate tenant isolation');
    return checks;
  } finally { await db.exec('ROLLBACK'); }
}
