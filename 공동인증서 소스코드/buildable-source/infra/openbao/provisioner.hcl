# Single-segment key management for this dedicated local dev Transit mount.
# Deployment must use dedicated tenant mounts or exact per-doctor policies.
# Provisioning cannot sign; signing has a distinct token. Scope further per tenant in deployment.
path "transit/keys/+" { capabilities = ["create", "read", "update"] }
path "pki/sign/vision-certi-doctor" { capabilities = ["update"] }
path "pki/revoke" { capabilities = ["update"] }
# No transit/export, backup, delete, rotate, policy management or root privileges.
path "transit/keys/+/config" { capabilities = ["deny"] }
path "transit/keys/+/rotate" { capabilities = ["deny"] }
path "transit/keys/+/import" { capabilities = ["deny"] }
path "transit/keys/+/import_version" { capabilities = ["deny"] }
