# Backups and restore

PostgreSQL holds everything the platform owns: tenants, customers, subscriptions, the ledger and the receipts, payments, the audit log, the RADIUS credentials (readable in clear only through the RADIUS views, ADR 0009), accounting (`radacct`) and the usage roll-ups. It is the only service that is backed up. This runbook covers the database; the loss of a whole host, hub and router peers included, is drill C of the failover-drill runbook (`failover-drill.md`).

## What is backed up, and what is not

| Service | Backed up | Why |
|---|---|---|
| PostgreSQL | Yes: a full backup every night plus continuous WAL archiving, 30 days kept, point-in-time recovery anywhere in them | Everything lives here |
| RabbitMQ | No | It holds events in flight only; the source is the `outbox_event` table, whose rows stay 7 days after publishing. After a broker loss, events accepted by the broker but not yet consumed are gone: billing's "matched payment with no ledger entry" alert (P4) and the daily reconciliation catch the money ones. To re-drive a window, set `published_at` to null on the rows of that window as `isp_owner`; the relay publishes them again and every consumer is idempotent by event id (E2). |
| Redis | No | Cache only: the token-version cache, sign-in limits and one-time codes. A loss costs a cold cache; nobody is signed out, token versions come from the database on a miss. |
| Configuration, secrets, certificates | By other means | Git, the inventory's vault and key escrow (`key-escrow.md`); Caddy obtains certificates again. The data disk itself is not backed up: a replacement host gets a new disk and the database comes back from the repository. |

## How it works

- **Where.** pgBackRest runs inside the `postgres` container (`infra/postgres/Dockerfile`, the official image plus pgBackRest) and writes to an S3-compatible bucket, one per inventory (`backup_s3_endpoint`, `backup_s3_bucket`, `backup_s3_region` in the inventory; the key pair in the vault), under `/corebalt-<inventory>`. Hetzner Object Storage in the host's region keeps it in the EU and off the host.
- **When.** PostgreSQL pushes every WAL segment to the repository as it fills, and at least every 5 minutes (`archive_timeout`), so at most 5 minutes of writes can be lost. `corebalt-backup.timer` takes a full backup every night at 01:00 UTC (04:00 in Nairobi). The last 30 full backups are kept, with the WAL that joins them (`stack_backup_retention_full`); older ones expire after each backup.
- **Encryption.** Every file, WAL included, is encrypted on the host before it leaves (AES-256-CBC, pgBackRest repository encryption) with `vault_backup_cipher_pass`, and travels over TLS. The storage provider sees ciphertext only. The passphrase's copies of record are the inventory's vault and key escrow; the host holds an operational copy in `/srv/corebalt/stack/pgbackrest.conf` (root and the postgres uid, mode 0400), because the host has to encrypt what it pushes. A passphrase lost on the host is restored from the vault; a passphrase lost everywhere makes every backup unreadable.
- **What each backup records.** `backupctl backup` counts seven tables that are never deleted from (tenants, customers, subscriptions, wallets, ledger entries, payments, audit rows) before the backup starts and annotates the backup with those counts and the Flyway schema version. The drill checks the restore against them.
- **The drill.** `corebalt-restore-drill.timer` runs `backupctl drill` on the first Sunday of every month at 03:00 UTC: it restores the newest backup into a scratch instance under `/srv/corebalt/drill` (the encrypted data disk: a restore holds the RADIUS credentials in clear), with archiving off and no network listener, replays the archived WAL to its end, then checks that the schema version is the annotated one, that every annotated table has at least the annotated rows, that every ledger entry's balance follows from the previous one (B3) and that every wallet's cached balance is its last entry's (B4). The scratch instance is removed and its directory wiped whatever the outcome.
- **Metrics and alerts.** Both commands write `/srv/corebalt/metrics/corebalt_backup.prom` and `corebalt_backup_drill.prom` for node_exporter's textfile collector; `infra/monitoring/rules/backups.yml` raises `CorebaltBackupStale` (newest good backup older than 26 hours), `CorebaltBackupFailed`, `CorebaltBackupMetricsMissing`, `CorebaltRestoreDrillFailed` and `CorebaltRestoreDrillOverdue` (no drill in 40 days). The rules are evaluated once the monitoring stack is deployed; until then `ops backup-status` is the check.

## Commands

| From the operator's machine | On the host (`/srv/corebalt/stack`) | Does |
|---|---|---|
| `infra\ops\ops.ps1 backup <inventory>` | `sudo ./backupctl backup` | A full backup now. Run it once after the first deploy, and before risky work (a migration you are unsure of, a bulk import). |
| `infra\ops\ops.ps1 drill <inventory>` | `sudo ./backupctl drill` | The restore drill now (after the first backup, after changing anything about backups, when the alert says it is overdue). |
| `infra\ops\ops.ps1 backup-status <inventory>` | `sudo ./backupctl status` | The repository (`pgbackrest info`) and the last backup and drill results. |
| | `sudo ./backupctl stanza` | Creates the stanza if missing and checks archiving; `stackctl deploy` runs it after the database is up. |
| | `sudo ./backupctl restore [--target "<timestamp>"]` | Disaster recovery, below. |
| | `journalctl -u corebalt-backup`, `journalctl -u corebalt-restore-drill`, `systemctl list-timers 'corebalt-*'` | What the timers did and when they run next. |

## When an alert fires

| Alert | First look | Usual causes |
|---|---|---|
| `CorebaltBackupFailed`, `CorebaltBackupStale` | `journalctl -u corebalt-backup -n 100` on the host; `backupctl status` | The bucket unreachable or its key pair rotated (fix the vault value, `ops provision`); the data disk locked after a reboot (`ops unlock`, then `ops backup`); the disk full |
| `CorebaltBackupMetricsMissing` | `systemctl status corebalt-backup.timer`; `ls /srv/corebalt/metrics` | The disk locked; a host that never backed up (`ops backup`); node_exporter not reading the directory |
| `CorebaltRestoreDrillFailed` | `journalctl -u corebalt-restore-drill -n 200` | A restore error (the repository, the passphrase); a check failed: a row count below the annotated one or a ledger inconsistency means the backup does not hold what the database held, treat it as a data incident and keep the journal output |
| `CorebaltRestoreDrillOverdue` | `systemctl list-timers 'corebalt-*'` | The host was off or locked on the first Sunday: `ops drill` |

## Restore procedures

Every restore is run by the operator, never automated. In each, PostgreSQL replays the archived WAL after the restore and promotes to a new timeline; take a full backup (`ops backup`) as soon as the stack is back so the next drill restores a backup from the new timeline.

### A. Disaster recovery: the host is gone

1. Build the replacement host as in `README.md` "Order on a new server", with the same inventory and vault: `bootstrap`, `init-disk` (a new LUKS passphrase, into escrow), `provision`. The stack is installed but nothing is deployed.
2. Give the host its release. If you still have the old host's `/srv/corebalt/stack/images.env`, copy it to the same place on the new host and go to step 3. Otherwise `infra\ops\ops.ps1 deploy <inventory> -Release <sha>` with the commit that was running (the `deployed-<inventory>` tag in GHCR, or the last deploy's output). This deploy creates an empty database and migrates it, which the next steps throw away; its archiving refuses to write into the repository because the system id differs, so the repository stays intact. Then stop it: `sudo /srv/corebalt/stack/stackctl stop`.
3. On the host, empty the database directory: `sudo find /srv/corebalt/postgres -mindepth 1 -delete` (the directory itself stays, owned by uid 999).
4. `sudo /srv/corebalt/stack/backupctl restore` (the newest backup and every archived WAL after it), or `--target "2026-10-22 10:15:00+00"` for a point in time.
5. `sudo systemctl start corebalt-stack`. PostgreSQL recovers (minutes at most; `docker logs corebalt-postgres-1` shows the replay), the services start behind their health checks.
6. Check: `backupctl status`, `stackctl status`, sign in to the admin app, a customer's wallet statement. Then `ops backup`.
7. Point the DNS records at the new host. Routers reach the hub by its public key: restore the hub's key from escrow (`key-escrow.md`) and replay the escrowed peer list, so no router is re-onboarded: `failover-drill.md`, drill C, steps 3 to 5.

### B. Point-in-time recovery on the same host (a bad change at a known time)

1. Decide the target time (UTC) from the audit log or the API logs; everything after it is lost. Payments received after the target are not lost for good: the daily reconciliation and the M-Pesa transaction status find them again, and every other change after it must be redone by hand.
2. `sudo /srv/corebalt/stack/stackctl stop`.
3. Keep the current directory for later inspection and give the restore an empty one: `sudo mv /srv/corebalt/postgres /srv/corebalt/postgres.before-pitr-$(date -u +%Y%m%dT%H%M)` then `sudo install -d -o 999 -g 999 -m 0700 /srv/corebalt/postgres`.
4. `sudo /srv/corebalt/stack/backupctl restore --target "2026-10-22 10:15:00+00"`.
5. `sudo systemctl start corebalt-stack`, check as in A, then `ops backup`. Remove the kept directory once nothing more is needed from it (it holds personal data: `sudo find ... -delete`).

### C. Restore in place after corruption

As B without `--target`: the newest backup plus every archived WAL, which is everything up to the last archived segment (at most 5 minutes before the failure).

### D. Look at old data without touching the live database

The drill's scratch instance, by hand, for a table or a row from the newest backup (or `--set=<label>` for an older one, labels from `backupctl status`):

```sh
cd /srv/corebalt/stack
image=$(sed -n 's/^POSTGRES_IMAGE=//p' images.env)
sudo install -d -o 999 -g 999 -m 0700 /srv/corebalt/drill && sudo find /srv/corebalt/drill -mindepth 1 -delete
sudo docker run --rm -u postgres -v /srv/corebalt/drill:/var/lib/postgresql/data \
    -v /srv/corebalt/stack/pgbackrest.conf:/etc/pgbackrest/pgbackrest.conf:ro "$image" pgbackrest --stanza=main restore
sudo docker run -d --name corebalt-drill -v /srv/corebalt/drill:/var/lib/postgresql/data \
    -v /srv/corebalt/stack/pgbackrest.conf:/etc/pgbackrest/pgbackrest.conf:ro "$image" postgres -c archive_mode=off -c listen_addresses=
# wait until: sudo docker exec -u postgres corebalt-drill psql -Atc 'select pg_is_in_recovery()'  prints f
sudo docker exec -u postgres corebalt-drill pg_dump -d isp -t billing_ledger_entry > /srv/corebalt/drill-ledger.sql
sudo docker rm -f corebalt-drill && sudo find /srv/corebalt/drill -mindepth 1 -delete
```

Keep the extract on the data disk and delete it when done; it is personal data. Never start the scratch instance with archiving on: it would push a second timeline into the repository.

## Changes to the setup

- **Rotating the bucket's key pair.** New pair at the provider, `ansible-vault edit` the two values, `ops provision` (the stack restarts to pick the file up), then `ops backup`.
- **The cipher passphrase cannot be changed in place**: the repository is encrypted with it. To rotate, set a new `stack_backup_repo_path` and passphrase, provision, take a full backup, and keep the old repository readable (old passphrase in escrow) until its backups would have expired.
- **A PostgreSQL major upgrade** is a procedure, not a deploy: `pg_upgrade` on the data directory, `pgbackrest --stanza=main stanza-upgrade`, then a full backup. A release that only bumps the image (minor version, pgBackRest) just restarts the database for a few seconds.
- **Staging** backs up the same way into its own bucket; it holds only the fake tenants and customers the onboarding runbook creates there (docs/02), so its backups carry no production data.

## Compliance

The repository is a second copy of personal data and of the RADIUS credentials outside Kenya; the safeguard recorded for it is in docs/08 (Compliance, cross-border transfer): encryption with a passphrase the storage provider never has, TLS, one key pair per environment, 30 days of retention, the drill on the encrypted disk and wiped, no copy outside the EU.
