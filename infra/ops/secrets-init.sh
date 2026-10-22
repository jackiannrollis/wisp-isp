#!/usr/bin/env bash
# Creates an inventory's secrets file (decisions.md 2026-10-21), run inside the ops container by
# ops.ps1/ops.sh "secrets <inventory>": random passwords and keys are generated here, the values only
# the operator knows are typed at prompts, and the file is written encrypted with ansible-vault (the
# vault password is asked for twice and goes into the password manager: without it the file is
# unreadable). Nothing is printed, logged or passed as an argument. Refuses to overwrite an existing
# file; the generated master key, signing key and vault password belong in key escrow
# (infra/ops/runbooks/key-escrow.md).
set -euo pipefail
inventory=${1:?usage: secrets-init.sh <inventory>}
dir="inventories/$inventory/group_vars/all"
target="$dir/vault.yml"
[ -d "inventories/$inventory" ] || { echo "no inventory at infra/ansible/inventories/$inventory" >&2; exit 1; }
[ ! -e "$target" ] || { echo "$target exists; rotate single values by hand (ansible-vault edit)" >&2; exit 1; }
mkdir -p "$dir"

ask() { # var prompt [secret]
    local value
    if [ "${3:-}" = secret ]; then
        read -r -s -p "$2: " value; echo
    else
        read -r -p "$2: " value
    fi
    [ -n "$value" ] || { echo "a value is required" >&2; exit 1; }
    printf -v "$1" '%s' "$value"
}
random() { openssl rand -hex 24; }

echo "Values only you have (the rest is generated):"
ask acme_token "DNS provider API token for the wildcard certificate (zone DNS edit scope)" secret
ask admin_email "First platform admin's email"
ask admin_password "First platform admin's temporary password (changed at first sign-in)" secret
ask ghcr_user "GitHub user the hosts pull images as"
ask ghcr_token "GHCR token with read:packages only (the hosts pull with it)" secret
read -r -s -p "Optional: a GHCR token with write:packages, for marking deployed images (Enter to skip): " ghcr_tag_token; echo
ask backup_s3_key "Backup bucket (S3-compatible Object Storage) access key, for this inventory's bucket only"
ask backup_s3_key_secret "Backup bucket secret key" secret

kid="k$(date -u +%Y%m%d%H%M)"
# The repository cipher passphrase: every backup is unreadable without it (key escrow).
backup_cipher_pass=$(openssl rand -hex 32)
jwt_key=$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 2>/dev/null \
    | openssl pkcs8 -topk8 -nocrypt -outform DER | openssl base64 -A)
master_key=$(openssl rand -base64 32)

tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
chmod 0600 "$tmp"
cat > "$tmp" <<EOF
# Secrets of inventory "$inventory", created $(date -u +%Y-%m-%dT%H:%MZ) by infra/ops/secrets-init.sh.
vault_postgres_superuser_password: $(random)
vault_db_owner_password: $(random)
vault_db_app_password: $(random)
vault_db_worker_password: $(random)
vault_radius_db_password: $(random)
vault_rabbitmq_password: $(random)
vault_redis_password: $(random)
vault_master_key: $master_key
vault_jwt_kid: $kid
vault_jwt_private_key: $jwt_key
vault_jwt_retired_keys: ""
vault_acme_dns_api_token: $acme_token
vault_bootstrap_admin_email: $admin_email
vault_bootstrap_admin_password: $admin_password
vault_ghcr_user: $ghcr_user
vault_ghcr_token: $ghcr_token
vault_ghcr_tag_token: $ghcr_tag_token
vault_backup_s3_key: $backup_s3_key
vault_backup_s3_key_secret: $backup_s3_key_secret
vault_backup_cipher_pass: $backup_cipher_pass
EOF
echo "Choose the vault password (it goes into the password manager; it is asked for on every provision and deploy):"
ansible-vault encrypt --output "$target" "$tmp"
echo "Written: infra/ansible/$target (encrypted). Put the vault password, the master key, the signing key and the backup cipher passphrase into escrow now: infra/ops/runbooks/key-escrow.md."
