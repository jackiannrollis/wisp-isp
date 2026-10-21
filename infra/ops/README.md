# Operations

Everything needed to run staging and production (docs/09 step 9, decisions.md 2026-10-21). Staging
comes first and runs for at least a week before production. Production deploys are always run by the
operator, never by an automated session.

## Images

CI publishes three images to the GitHub Container Registry on every push to `main`, after every check
passed (`.github/workflows/ci.yml`, job `publish`):

| Image | Built from |
|---|---|
| `ghcr.io/<owner>/corebalt-isp-backend` | `backend/` (API and tunnel worker) |
| `ghcr.io/<owner>/corebalt-isp-freeradius` | `radius/` |
| `ghcr.io/<owner>/corebalt-isp-wireguard` | `infra/wireguard/` (local development only; hosts run wg-quick) |

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

Leave the `nairobi` group out of a real inventory until the data centre is chosen (Q2); the example shows its shape.

### Order on a new server

1. Create the server (Debian 13) with your SSH key, plus a separate volume for the data disk (do not let Hetzner format or mount it).
2. `infra\ops\ops.ps1 bootstrap staging -Limit staging-1`. This first run connects as root. It:
   - creates the operator account and installs your keys;
   - turns off password and root login;
   - sets the firewall: 80/443 and the WireGuard port public, SSH only from `ssh_allowed_cidrs`;
   - turns on unattended security upgrades, time sync and Docker;
   - starts the WireGuard hub.

   It refuses to turn the firewall on if your own address isn't in `ssh_allowed_cidrs`. It reports that the data disk isn't encrypted yet.
3. `infra\ops\ops.ps1 init-disk staging -Limit staging-1`. It asks you to type the disk path back, refuses a disk that already holds anything, and asks for the new LUKS passphrase twice. Put the passphrase into key escrow first.
4. `infra\ops\ops.ps1 provision staging`. From now on every run is this one, as the operator. It is safe to repeat; a second run changes nothing.

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
