#!/bin/bash
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

apt-get update -qq
apt-get install -y -qq docker.io python3 python3-ldap3 python3-cryptography curl ca-certificates cron
systemctl enable --now docker
install -d -m 0755 /opt/vision-certi
curl -fsS -H 'Metadata-Flavor: Google' \
  'http://metadata.google.internal/computeMetadata/v1/instance/attributes/vision-certi-crl-script' \
  -o /opt/vision-certi/refresh_yessign_crl.py
chmod 0755 /opt/vision-certi/refresh_yessign_crl.py
printf '%s\n' '17 * * * * root /usr/bin/python3 /opt/vision-certi/refresh_yessign_crl.py >> /var/log/vision-certi-crl.log 2>&1' \
  > /etc/cron.d/vision-certi-crl
chmod 0644 /etc/cron.d/vision-certi-crl
systemctl enable --now cron

install -d -m 0700 -o 100 -g 1000 /var/lib/vision-certi/openbao/data
install -d -m 0700 -o 100 -g 1000 /var/lib/vision-certi/openbao/tls
install -d -m 0700 -o 100 -g 1000 /var/lib/vision-certi/openbao/config
install -d -m 0700 -o 100 -g 1000 /var/lib/vision-certi/openbao/plugins

metadata_token="$(curl -fsS -H 'Metadata-Flavor: Google' \
  'http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')"
curl -fsS --retry 5 --retry-delay 3 \
  -H "Authorization: Bearer ${metadata_token}" \
  'https://secretmanager.googleapis.com/v1/projects/hahattalk/secrets/vision-certi-openbao-tls/versions/latest:access' \
  | python3 -c '
import base64,json,sys
from pathlib import Path
payload=json.loads(base64.b64decode(json.load(sys.stdin)["payload"]["data"]))
directory=Path("/var/lib/vision-certi/openbao/tls")
(directory/"server.pem").write_text(payload["certificate"])
(directory/"server-key.pem").write_text(payload["privateKey"])
'
unset metadata_token
chown 100:1000 /var/lib/vision-certi/openbao/tls/*.pem
chmod 0400 /var/lib/vision-certi/openbao/tls/*.pem

cat > /var/lib/vision-certi/openbao/config/openbao.hcl <<'EOF'
ui = false
api_addr = "https://10.71.0.10:8200"
cluster_addr = "https://10.71.0.10:8201"
plugin_directory = "/openbao/plugins"
plugin_auto_download = true

plugin "kms" "gcpckms" {
  image = "ghcr.io/openbao/openbao-plugin-kms-gcp:v0.1.0@sha256:ed0b6e44716dfb77286dc53e8ee2322e41c8e9b14d9652a20f71dd8384f31842"
}

storage "raft" {
  path = "/openbao/data"
  node_id = "vision-certi-openbao-1"
  performance_multiplier = 1
}

listener "tcp" {
  address = "10.71.0.10:8200"
  cluster_address = "10.71.0.10:8201"
  tls_cert_file = "/openbao/tls/server.pem"
  tls_key_file = "/openbao/tls/server-key.pem"
}

seal "gcpckms" {
  project = "hahattalk"
  region = "asia-northeast3"
  key_ring = "vision-certi-openbao"
  crypto_key = "openbao-unseal"
}
EOF
chown 100:1000 /var/lib/vision-certi/openbao/config/openbao.hcl
chmod 0400 /var/lib/vision-certi/openbao/config/openbao.hcl

docker pull ghcr.io/openbao/openbao@sha256:6d2b93856e3fcf7b18ad855a0b51eaba474dc8b79cf554379ea32034797d2acf
if ! docker container inspect vision-certi-openbao >/dev/null 2>&1; then
  docker run -d --name vision-certi-openbao --restart unless-stopped \
    --network host --memory-swappiness 0 \
    --mount type=bind,src=/var/lib/vision-certi/openbao/data,dst=/openbao/data \
    --mount type=bind,src=/var/lib/vision-certi/openbao/tls,dst=/openbao/tls,readonly \
    --mount type=bind,src=/var/lib/vision-certi/openbao/config,dst=/openbao/config,readonly \
    --mount type=bind,src=/var/lib/vision-certi/openbao/plugins,dst=/openbao/plugins \
    ghcr.io/openbao/openbao@sha256:6d2b93856e3fcf7b18ad855a0b51eaba474dc8b79cf554379ea32034797d2acf \
    server -config=/openbao/config/openbao.hcl
else
  docker start vision-certi-openbao >/dev/null 2>&1 || true
fi
echo 'Vision-Certi OpenBao container started; initialization is a separate protected step.'
