# Key escrow

The secrets that cannot be regenerated are kept in two places outside any server: the password manager and a sealed offline copy. Losing one of them loses data or every tenant's credentials; none may exist only on a host or only on one person's machine.

## What is escrowed

| Secret | Where it lives in use | Losing it means | Escrow |
|---|---|---|---|
| The vault password of each inventory | Typed at every `ops provision` and `ops deploy` | The inventory's `vault.yml` is unreadable, and with it every secret below that it holds | Password manager; sealed copy |
| The encrypted `vault.yml` of each inventory | `infra/ansible/inventories/<inventory>/group_vars/all/` on the operator's machine (git ignores it) | The same, unless the values below are escrowed one by one | Password manager, as an attachment, updated after every change |
| Tenant-secret master key, `vault_master_key` (`ISP_MASTER_KEY`, docs/07) | The vault; the host's `.env` | Every tenant's M-Pesa and SMS credentials are unreadable; each tenant would re-enter them | Password manager; sealed copy |
| Token signing key, `vault_jwt_kid` and `vault_jwt_private_key` (ADR 0010) | The vault; the host's `.env` | Everyone is signed out once; a new key is generated and the old `kid` retired | Password manager (a rotation makes the old one worthless once its tokens expire) |
| pgBackRest cipher passphrase, `vault_backup_cipher_pass` | The vault; `/srv/corebalt/stack/pgbackrest.conf` on the host | Every backup and WAL segment is unreadable: no restore, ever | Password manager; sealed copy |
| WireGuard hub private key, `/var/lib/wireguard/hub.key` on each hub host | The host only; generated on the hub's first start | Routers trust the hub by its public key: a new key means every router must be re-onboarded | Password manager; sealed copy. Exported once, over SSH: `sudo cat /var/lib/wireguard/hub.key`; never by mail or chat |
| LUKS data-disk passphrase of each host (`manual` unlock mode) | Nowhere on the host; typed at `ops unlock` | The data disk is unreadable after the next reboot; the database exists only in the backups | Password manager; sealed copy |
| cosign signing key and its password (`infra/ops/README.md`, Signing) | GitHub Actions secrets `COSIGN_PRIVATE_KEY`, `COSIGN_PASSWORD` | Releases cannot be signed; a new pair means a new `infra/ops/cosign.pub` and re-signing what is deployed | Password manager; sealed copy |

Regenerable, so not escrowed: the GHCR tokens, the DNS provider's API token, the bucket's S3 key pair (new ones at the provider, `ansible-vault edit`, `ops provision`), the Let's Encrypt account (Caddy makes another).

## Where

1. **Password manager.** A shared vault "Corebalt infrastructure" with at least two members (the operator and one trustee), 2FA on both accounts, and the manager's own recovery kit kept by the other member. One item per secret, named `<inventory>/<name>` (or `<host>/<name>` for host keys and passphrases), with the date and who created it in the notes. `vault.yml` goes in as an attachment on its inventory's item.
2. **Sealed offline copy.** A printed copy of each secret (the cosign key and `vault.yml` on a USB stick beside it) in a tamper-evident envelope, numbered, signed across the seal, kept locked at a second location, not where the password manager's recovery kit is. The envelope is not opened to look: it is opened only when the password manager is unavailable, and then a new envelope is sealed afterwards. After any rotation, a new envelope replaces the old one, which is shredded.
3. **Log.** A note in the password manager, "escrow log": date, action (added, rotated, verified, opened, destroyed), who, envelope number.

## When

| Moment | What goes into escrow, right then |
|---|---|
| `infra\ops\ops.ps1 secrets <inventory>` | The vault password, `vault.yml`, the master key, the signing key, the cipher passphrase (the values from `ansible-vault view`) |
| `infra\ops\ops.ps1 init-disk` | The LUKS passphrase you typed |
| `infra\ops\ops.ps1 bootstrap` (the hub's first start) | The hub's private key, and its public key in the notes for checking |
| The cosign setup | `cosign.key` and its password |
| Any rotation | The new value; the old one destroyed only after nothing depends on it (an old cipher passphrase stays until its repository's backups have expired) |

The sealed copy is made before the first production deploy and refreshed within a week of any change.

## Verification, every quarter

Check without exposing anything:

- The vault password opens the vault: `infra\ops\ops.ps1 shell`, then `ansible-vault view inventories/<inventory>/group_vars/all/vault.yml | grep -c '^vault_'` with the password manager's copy.
- The escrowed hub key matches the running hub: `wg pubkey` on the escrowed key, compared with the public key the hub prints (`sudo corebalt-wg-hub public-key` on the host, or `journalctl -u corebalt-wg-hub`).
- The escrowed cipher passphrase matches the host's file: compare `sha256sum` of the escrowed value with `sudo grep '^repo1-cipher-pass=' /srv/corebalt/stack/pgbackrest.conf | cut -d= -f2- | sha256sum` on the host. The monthly restore drill proves the host's copy restores backups.
- The LUKS passphrase: it is typed at every unlock after a reboot; a reboot in a maintenance window within the quarter counts as its check.
- The envelope: present, seal intact, number as logged.

Log the check.

## Using an escrowed secret

- Rebuilding a lost `vault.yml`: write the values into a file shaped like `infra/ansible/inventories/example/group_vars/all/vault.yml`, then in `ops shell`: `ansible-vault encrypt --output inventories/<inventory>/group_vars/all/vault.yml <file>`, and shred the file.
- Restoring a hub key on a replacement host: `sudo systemctl stop corebalt-wg-hub`, write the key to `/var/lib/wireguard/hub.key` (root, mode 0600), `sudo systemctl start corebalt-wg-hub`; the service recomputes the public key and prints it: check it against the escrow note. The router peers come back by themselves from the database within a minute (`corebalt-routersync.timer`, ADR 0013).
- A restore from backups needs only the vault (the passphrase is in `pgbackrest.conf` after `ops provision`): `backups.md`.
- The master key and the signing key reach a host through the vault and `ops provision`; they are never typed on a host.
