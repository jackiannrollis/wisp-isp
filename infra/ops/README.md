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
