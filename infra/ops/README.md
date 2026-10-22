# Operations

Everything needed to run staging and production (docs/09 step 9, decisions.md 2026-10-21). Staging
comes first and runs for at least a week before production. Production deploys are always run by the
operator, never by an automated session.

## Images

CI publishes five images to the GitHub Container Registry on every push to `main`, after every check
passed (`.github/workflows/ci.yml`, job `publish`):

| Image | Built from |
|---|---|
| `ghcr.io/<owner>/corebalt-isp-backend` | `backend/` (API and tunnel worker) |
| `ghcr.io/<owner>/corebalt-isp-freeradius` | `radius/` |
| `ghcr.io/<owner>/corebalt-isp-wireguard` | `infra/wireguard/` (local development only; hosts run the same script as a service) |
| `ghcr.io/<owner>/corebalt-isp-web` | `infra/web/` from the repository root: both Angular apps and Caddy with the DNS plugins |
| `ghcr.io/<owner>/corebalt-isp-postgres` | `infra/postgres/`: the official PostgreSQL 17 image plus pgBackRest (backups) |

- **Tags.** Each image is tagged `sha-<full commit SHA>`, never `latest`. The job summary lists each digest.
- **Deploys go by digest, not by tag.** The deploy playbook resolves `sha-<commit>` to its digest once, checks the signature of that digest, and writes `image@sha256:…` on the host. A tag that is later moved can't change what runs.
- **Visibility.** The packages are private, like the repository. Hosts pull with a read-only token (`read:packages`).

### Signing

Images are signed with the project's cosign key. Nothing is uploaded to the public transparency log: keyless signing would publish this private repository's name, workflow and commits there.

One-time setup by the operator:

1. Create the key pair on a trusted machine, with a strong password:
   `docker run --rm -it -u 0 -v cosign-keys:/work -w /work ghcr.io/sigstore/cosign/cosign:v3.1.4 generate-key-pair`
2. In the repository settings add two secrets: `COSIGN_PRIVATE_KEY` (the content of `cosign.key`) and `COSIGN_PASSWORD`.
3. Commit `cosign.pub` as `infra/ops/cosign.pub`. The deploy playbook refuses an unsigned digest once that file exists.
4. Put `cosign.key` and its password into key escrow (see the key-escrow runbook), then delete the volume: `docker volume rm cosign-keys`.

Until the secrets exist, the publish job pushes unsigned images and says so in a warning.

Verification, by hand (cosign v3, no transparency log):
`cosign verify --key infra/ops/cosign.pub --insecure-ignore-tlog=true ghcr.io/<owner>/corebalt-isp-backend@sha256:…`

### Retention

`.github/workflows/image-retention.yml` runs weekly and runs `ghcr-retention.sh`. For each package it keeps:

- the newest 30 release images;
- every image tagged `deployed-*` or `previous-*` (the deploy playbook sets these);
- the signature (tag `sha256-<digest>`) of every image it keeps.

It never touches untagged versions or versions with any other tag. Deleting is permanent, so the run is only a plan until the repository variable `IMAGE_RETENTION_APPLY` is `true`, or a manual run sets `apply`.

## Provisioning hosts

Ansible runs from the ops image (`infra/ops/Dockerfile`), so a Windows machine needs only Docker Desktop.
The image holds ansible-core, the pinned collections, cosign and crane.

- `infra\ops\ops.ps1` is the Windows entry point; `infra/ops/ops.sh` is the same on Linux or macOS.
- The repository is mounted read-only.
- Your SSH key is mounted read-only; the default is `~/.ssh/id_ed25519`, and `-SshKey` names another.
- Host keys are trusted on first use and pinned in the Docker volume `corebalt-ops-ssh`.

### Inventory

Copy `infra/ansible/inventories/example/` to `infra/ansible/inventories/staging/` (later `production/`).
git ignores every inventory except the example. Fill in:

- each host's `ansible_host`, and its hub tunnel address `wg_hub_address`;
- `platform_domain`;
- `operator_ssh_public_keys`: your public key(s);
- `ssh_allowed_cidrs`: the addresses you connect from. SSH is closed to everything else.
- `data_volume_device`: the data disk, e.g. `/dev/disk/by-id/scsi-0HC_Volume_<id>` for a Hetzner Volume.
- `data_volume_unlock`: see "Data disk" below.
- `backup_s3_endpoint`, `backup_s3_bucket`, `backup_s3_region`: the inventory's backup bucket (see Backups).

Leave the `nairobi` group out of a real inventory until the data centre is chosen (Q2); the example shows its shape.

### Secrets

Each inventory keeps its secrets in `group_vars/all/vault.yml`, encrypted with ansible-vault. `infra\ops\ops.ps1 secrets staging` creates it:

- it generates the database, RabbitMQ and Redis passwords, the tenant-secret master key, the token signing key and the backup repository's cipher passphrase;
- it asks you for the values only you have: the DNS provider's API token (zone DNS edit scope, for the wildcard certificate), the first platform admin's email and temporary password, a GHCR token with `read:packages` the hosts pull images with, and the S3 key pair of the inventory's backup bucket;
- it asks for a new vault password, twice, and writes the file encrypted. Nothing is printed or logged.

Keep the vault password in the password manager: every `provision` and `deploy` asks for it. The master key, the signing key, the backup cipher passphrase and the vault password belong in key escrow (`runbooks/key-escrow.md`). To change one value later: `infra\ops\ops.ps1 shell`, then `ansible-vault edit inventories/staging/group_vars/all/vault.yml`.

The example inventory's `vault.yml` is plain text with obvious placeholders, so the syntax check and the converge test can run without a password.

### Order on a new server

1. Create the server (Debian 13) with your SSH key, plus a separate volume for the data disk (do not let Hetzner format or mount it), and an Object Storage bucket for the backups in the same region, with its own S3 key pair. Point the DNS records at the server: `<domain>`, `admin.<domain>`, `pay.<domain>` and `*.<domain>`.
2. `infra\ops\ops.ps1 secrets staging` (once per inventory, see above).
3. `infra\ops\ops.ps1 bootstrap staging -Limit staging-1`. This first run connects as root. It:
   - creates the operator account and installs your keys;
   - turns off password and root login;
   - sets the firewall: 80/443 and the WireGuard port public, SSH only from `ssh_allowed_cidrs`;
   - turns on unattended security upgrades, time sync and Docker;
   - starts the WireGuard hub.

   It refuses to turn the firewall on if your own address isn't in `ssh_allowed_cidrs`. It reports that the data disk isn't encrypted yet.
4. `infra\ops\ops.ps1 init-disk staging -Limit staging-1`. It asks you to type the disk path back, refuses a disk that already holds anything, and asks for the new LUKS passphrase twice. Put the passphrase into key escrow first.
5. `infra\ops\ops.ps1 provision staging`. From now on every run is this one, as the operator. It installs the stack (below) now that the disk is mounted. It is safe to repeat; a second run changes nothing.
6. Deploy a release (`deploy`, see Deploying). The stack stays down until then.
7. `infra\ops\ops.ps1 backup staging`, then `infra\ops\ops.ps1 drill staging`: the first backup and the first restore drill, so the host starts from a known-good state (see Backups).

`-Check` shows what a run would change without changing it.

### Data disk

The database, backups and every other volume with customer data live on a LUKS2 (Argon2id) disk mounted at `/srv/corebalt`. docs/08 requires this: the RADIUS credentials are in clear text there (ADR 0009). The `data_volume_unlock` setting decides how the disk opens after a reboot:

| Mode | After a reboot | Protects against | Unattended reboots |
|---|---|---|---|
| `manual` (default) | Everything that needs the disk stays down until you run `infra\ops\ops.ps1 unlock staging -Limit staging-1` and type the passphrase. It is never stored on the host. | A stolen, detached or disposed disk, and a copy of the host's root disk | Never: security upgrades install, and you reboot in a window and unlock |
| `keyfile` | Opens by itself from a key file on the root disk | A detached or disposed data disk only | Yes, at 03:30 when an upgrade needs it |

### Firewall and Docker

ufw closes the host, but Docker writes its own rules for published container ports, which bypass ufw.
The stack therefore publishes only 80 and 443 (Caddy) and binds every other port to 127.0.0.1 or to the
hub's tunnel address. A new published port must follow that rule.

### Testing the playbooks

`bash infra/ansible/tests/converge.sh` runs the whole order above against a throwaway Debian 13 container
(systemd, sshd, a loop-file data disk):

- bootstrap, init-disk, provision;
- lock and unlock;
- a second provision that must change nothing;
- then it checks from outside that root login is refused, sshd is key-only, the firewall rules, the LUKS2 header, and that the hub and Docker are running.

CI runs it on every push. It cannot prove what only a real server shows: the Hetzner image, the cloud firewall, a real reboot, or kernel upgrades.

## Deploying

A release is a commit on `main` whose images CI published (every check passed). `infra\ops\ops.ps1 deploy staging` deploys the commit your checkout is at, so pull first; `-Release <sha>` names any other, including an older one.

What the deploy does:

1. In the ops container, `crane` resolves each image's `sha-<commit>` tag to its digest, and once `infra/ops/cosign.pub` exists `cosign` checks every digest's signature. A missing image means CI has not published that commit yet; an unsigned one is refused once the key is in place.
2. On each host, one at a time, it writes `images.env.new` with the digests and runs `stackctl deploy`, which pulls the images first, then recreates the services one at a time, each behind its health gate: `api-1` (runs the migrations), `api-2`, the tunnel worker, FreeRADIUS, the web front. While one API instance restarts the other keeps serving. If a service never gets healthy, `stackctl` rolls the previous release back and the deploy fails with the service's logs; the failed `images.env` is kept as `images.env.failed`.
3. With a write token in the vault, the deployed digests are tagged `deployed-<inventory>` and the previous ones `previous-<inventory>` in the registry, which the retention job keeps. Without one, a deployed image is kept only while it is among the last 30 releases.

`infra\ops\ops.ps1 rollback staging` swaps the previous release back the same way (`stackctl rollback`); the images are already on the host. `stackctl status` on a host shows the release, the previous one and every service.

Migrations are applied by `api-1` of the new release and are backwards compatible for one release (docs/02), so the previous API runs on the migrated schema; a rollback never undoes a migration.

What the stack test proves about this (`infra/stack/test.sh`, also in CI): a first deploy, a repeat that changes nothing, a release whose API never gets healthy being rolled back while the API keeps answering, and a rollback. What it cannot: pulling from GHCR by digest and the signature check, which run only in the real deploy.

## Backups

PostgreSQL is backed up with pgBackRest from inside the `postgres` container to an S3-compatible bucket in the EU (Hetzner Object Storage in the host's region), one bucket per inventory. WAL segments reach it within five minutes, a full backup runs every night at 01:00 UTC, and the last 30 nightly backups are kept with their WAL, which gives 30 days of point-in-time recovery. Everything is encrypted on the host before it leaves, with the vault's cipher passphrase: the storage provider sees ciphertext only, and a backup is unreadable without the passphrase, which is why it is in key escrow. On the first Sunday of every month the host restores the newest backup into a scratch instance on the data disk, replays the archived WAL, checks the schema version, the row counts and the ledger, and writes pass or fail as metrics; a backup older than 26 hours, a failed run and a failed or overdue drill alert once the monitoring stack is up (`infra/monitoring/rules/backups.yml`). RabbitMQ and Redis are not backed up, on purpose. The runbook with the restore procedures: `runbooks/backups.md`.

| Command | Does |
|---|---|
| `infra\ops\ops.ps1 backup staging` | a full backup now; once after the first deploy, and before risky work |
| `infra\ops\ops.ps1 drill staging` | the restore drill now |
| `infra\ops\ops.ps1 backup-status staging` | the repository and the last backup and drill results |

On the host, `backupctl` beside `stackctl` does the same (`backup`, `drill`, `status`), plus `stanza` (run by every deploy) and `restore` for disaster recovery; the timers `corebalt-backup` and `corebalt-restore-drill` run the nightly backup and the monthly drill, and skip, like the stack unit, while the data disk is locked.

## The stack

`infra/stack/compose.yml` is the application stack of a core or staging host, installed by the `stack` role into `/srv/corebalt/stack` on the encrypted disk together with `.env` (the inventory's values and vault, root only) and `images.env` (the deployed image digests, written by `deploy`). A systemd unit, `corebalt-stack`, runs `docker compose up --wait` with both files; it is skipped, not failed, while the data disk is locked or nothing is deployed.

| Service | Image | Reachable from |
|---|---|---|
| `web` | `corebalt-isp-web`: Caddy with the Cloudflare and Hetzner DNS plugins, serving both Angular apps and proxying the API | the internet, 80 and 443 |
| `api-1`, `api-2` | `corebalt-isp-backend` | `web` only. `api-1` runs the migrations, the scheduled jobs and the outbox relay; `api-2` serves requests and consumes events, so no job runs twice |
| `tunnel-worker` | `corebalt-isp-backend`, profile `worker` (ADR 0011) | nothing; it reaches routers through the host's hub |
| `freeradius` | `corebalt-isp-freeradius` | routers, on the hub's tunnel address only (1812/1813 UDP) |
| `postgres` | `corebalt-isp-postgres`: the official image plus pgBackRest, part of the release (a release that changes it restarts the database for seconds) | the stack's network only |
| `rabbitmq`, `redis` | official images, pinned in the compose file | the stack's network only; the RabbitMQ UI on 127.0.0.1:15672 for an SSH tunnel |

Docker's published ports bypass ufw, so the rule is: `web` publishes 80 and 443, FreeRADIUS binds the tunnel address, everything else stays internal.

### Names and certificates

| Name | Serves |
|---|---|
| `<domain>` | M-Pesa and SMS callbacks (`/callbacks/*`); anything else redirects to the admin app |
| `admin.<domain>` | the admin app and its API |
| `pay.<domain>` | the portal for any provider: the pay page restricted customers reach (sign-in takes the provider from the account number) |
| `<tenant>.<domain>` | a tenant's portal, under the wildcard certificate |
| any other name over plain HTTP | a restricted customer's redirected browsing: sent to `pay.<domain>` |

Certificates come from Let's Encrypt through the ACME DNS-01 challenge, which the wildcard needs; `stack_acme_dns_provider` (cloudflare or hetzner) and the vault's API token drive it. The main domain, `admin` and `pay` get their own certificates. Caddy keeps them in `/srv/corebalt/caddy`.

The web front also sets the security headers (HSTS, CSP, no framing) and hides `/actuator`, so metrics stay internal.

### Testing the stack locally

`bash infra/stack/test.sh` builds the four images, starts this exact compose file with Caddy's own CA instead of ACME, `*.corebalt.localhost` names, named volumes and fake secrets on ports 8081/8443, loads the lab seed, validates the Caddyfile in DNS mode with both providers, and checks the routing, headers and caching from outside. Then the deploy scenarios, and the backups against a local S3 server (versitygw with a self-signed certificate): a full backup, the restore drill, a disaster recovery (the database volume removed and restored from the repository, the data checked), and the alert rules' unit tests with promtool. It leaves the stack running for a look at `https://admin.corebalt.localhost:8443` (the certificate is self-signed); `bash infra/stack/test.sh down` removes it. CI runs it on every push.

What it cannot prove: a real certificate from Let's Encrypt through your DNS provider, callbacks arriving from Safaricom's addresses, and a real Object Storage endpoint (the URI style and region of the provider).
