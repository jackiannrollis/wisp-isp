#!/usr/bin/env bash
# Backups of the stack's PostgreSQL with pgBackRest (docs/08; decisions.md 2026-10-21, 2026-10-22;
# runbook infra/ops/runbooks/backups.md). Installed by roles/stack as /srv/corebalt/stack/backupctl
# beside stackctl; run by the corebalt-backup and corebalt-restore-drill timers, by the backup
# playbook (ops backup, ops drill) and by hand. It runs in the stack directory with .env, images.env
# (the deployed postgres image) and pgbackrest.conf (the repository, its credentials and the cipher
# passphrase: root and the postgres uid only). pgBackRest itself runs inside the postgres container as
# the postgres user; nothing is installed on the host.
#
#   backupctl stanza     create the repository's stanza if missing and check that WAL archiving works
#                        (stackctl deploy runs it once the database is up; safe to repeat)
#   backupctl backup     a full backup now, annotated with the schema version and row counts; metrics
#   backupctl drill      restore the newest backup into a scratch instance on the data disk, replay the
#                        archived WAL, check schema, row counts and the ledger, write metrics, remove it
#   backupctl restore [--target "<timestamp>"]
#                        disaster recovery: restore into the stack's empty database directory, to the
#                        end of the archived WAL or to the target time; the stack must be stopped
#   backupctl status     the repository (pgbackrest info) and the last backup and drill results
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
# A host path for docker run; Git Bash (the local test) converts /d/... paths only when asked not to.
STACK_DIR=$(pwd -W 2>/dev/null || pwd)
export MSYS_NO_PATHCONV=1

envval() { sed -n "s/^$1=//p" "${2:-.env}" 2>/dev/null | head -1; }
DATA_DIR=$(envval DATA_DIR); DATA_DIR=${DATA_DIR:-/srv/corebalt}
# Metrics for node_exporter's textfile collector (the monitoring stack reads this directory).
METRICS_DIR=$(envval METRICS_DIR); METRICS_DIR=${METRICS_DIR:-$DATA_DIR/metrics}
# The drill's scratch store: a directory on the encrypted data disk (a restore holds the RADIUS
# credentials in clear, ADR 0009), or a Docker volume name in the local test.
DRILL_STORE=$(envval DRILL_STORE); DRILL_STORE=${DRILL_STORE:-$DATA_DIR/drill}
DRILL_NETWORK=$(envval DRILL_NETWORK)   # empty: Docker's default bridge (the repository is on the internet)
DRILL_NAME=corebalt-drill
DRILL_WAIT=${DRILL_WAIT:-900}
# Tables the drill compares with the counts annotated at backup time: none has a DELETE grant, so
# the restored count is never below the one counted before the backup started.
TABLES="tenancy_tenant customers_customer subscriptions_subscription billing_wallet billing_ledger_entry payments_payment identity_audit_log"
PY=$(command -v python3 || command -v python) || { echo "backupctl: python3 is needed to read pgbackrest's JSON" >&2; exit 2; }

compose() {
    local files=(-f compose.yml)
    [ -f compose.override.yml ] && files+=(-f compose.override.yml)
    docker compose "${files[@]}" --env-file .env --env-file images.env "$@"
}
pgbr() { compose exec -T -u postgres postgres pgbackrest --stanza=main "$@"; }
psql_live() { compose exec -T -u postgres postgres psql -X -Atq -d isp "$@"; }
psql_drill() { docker exec -u postgres "$DRILL_NAME" psql -X -Atq -d isp "$@"; }
quiet() { # <what> <command...>: one line on success, the output's tail on failure
    local what=$1 out; shift
    if out=$("$@" 2>&1); then echo "   ok   $what"; else echo "   FAIL $what"; echo "$out" | tail -15 | sed 's/^/        /'; return 1; fi
}
require_deployed() {
    [ -f images.env ] || { echo "backupctl: nothing deployed yet (no images.env); deploy a release first" >&2; exit 1; }
    [ -s pgbackrest.conf ] || { echo "backupctl: no pgbackrest.conf (roles/stack writes it from the inventory)" >&2; exit 1; }
}
counts_sql() { local t sql="" sep=""; for t in $TABLES; do sql+="${sep}select '$t', count(*) from $t"; sep=" union all "; done; echo "$sql"; }
schema_sql="select coalesce(max(version), '0') from flyway_schema_history where success"
# The newest successful backup: label, stop time (epoch), count of good backups, database bytes,
# repository bytes, repository status code.
info_summary() {
    pgbr --output=json info | "$PY" -I -c '
import json, sys
stanzas = json.load(sys.stdin)
s = stanzas[0] if stanzas else {}
ok = [b for b in s.get("backup", []) if not b.get("error")]
last = ok[-1] if ok else None
info = (last or {}).get("info", {})
repo = info.get("repository", {})
print(last["label"] if last else "-", last["timestamp"]["stop"] if last else 0, len(ok),
      info.get("size", 0), repo.get("size") or repo.get("delta") or 0, s.get("status", {}).get("code", 99))' | tr -d '\r'
}
backup_annotations() { # <label>: "key value" lines
    pgbr --output=json info | "$PY" -I -c '
import json, sys
for b in json.load(sys.stdin)[0].get("backup", []):
    if b["label"] == sys.argv[1]:
        for k, v in sorted(b.get("annotation", {}).items()):
            print(k, v)' "$1" | tr -d '\r'
}
write_metrics() { # <file>; the content on stdin, swapped in whole
    mkdir -p "$METRICS_DIR"
    local tmp="$METRICS_DIR/.$1.tmp"
    cat > "$tmp" && chmod 0644 "$tmp" && mv -f "$tmp" "$METRICS_DIR/$1"
}
write_backup_metrics() { # <started epoch> <rc>
    local label stop count db repo status now
    read -r label stop count db repo status < <(info_summary 2>/dev/null || echo "- 0 0 0 0 99")
    now=$(date +%s)
    write_metrics corebalt_backup.prom <<EOF
# HELP corebalt_backup_last_good_timestamp_seconds Stop time of the newest successful pgBackRest backup.
# TYPE corebalt_backup_last_good_timestamp_seconds gauge
corebalt_backup_last_good_timestamp_seconds $stop
# HELP corebalt_backup_last_run_timestamp_seconds When backupctl backup last ran.
# TYPE corebalt_backup_last_run_timestamp_seconds gauge
corebalt_backup_last_run_timestamp_seconds $now
# HELP corebalt_backup_last_run_success 1 when the last backup run succeeded, 0 when it failed.
# TYPE corebalt_backup_last_run_success gauge
corebalt_backup_last_run_success $([ "$2" = 0 ] && echo 1 || echo 0)
# HELP corebalt_backup_last_run_duration_seconds How long the last backup run took.
# TYPE corebalt_backup_last_run_duration_seconds gauge
corebalt_backup_last_run_duration_seconds $((now - $1))
# HELP corebalt_backup_count Successful backups in the repository.
# TYPE corebalt_backup_count gauge
corebalt_backup_count $count
# HELP corebalt_backup_database_bytes Size of the database in the newest backup.
# TYPE corebalt_backup_database_bytes gauge
corebalt_backup_database_bytes $db
# HELP corebalt_backup_repository_bytes Size of the newest backup in the repository (compressed, encrypted).
# TYPE corebalt_backup_repository_bytes gauge
corebalt_backup_repository_bytes $repo
# HELP corebalt_backup_repository_status pgBackRest status code of the repository (0 = ok).
# TYPE corebalt_backup_repository_status gauge
corebalt_backup_repository_status $status
EOF
}
write_drill_metrics() { # <started epoch> <rc> <stop epoch of the restored backup>
    local now; now=$(date +%s)
    {
        cat <<EOF
# HELP corebalt_backup_drill_last_run_timestamp_seconds When the restore drill last ran.
# TYPE corebalt_backup_drill_last_run_timestamp_seconds gauge
corebalt_backup_drill_last_run_timestamp_seconds $now
# HELP corebalt_backup_drill_success 1 when the last restore drill passed every check, else 0.
# TYPE corebalt_backup_drill_success gauge
corebalt_backup_drill_success $([ "$2" = 0 ] && echo 1 || echo 0)
# HELP corebalt_backup_drill_duration_seconds How long the last restore drill took.
# TYPE corebalt_backup_drill_duration_seconds gauge
corebalt_backup_drill_duration_seconds $((now - $1))
# HELP corebalt_backup_drill_backup_timestamp_seconds Stop time of the backup the last drill restored.
# TYPE corebalt_backup_drill_backup_timestamp_seconds gauge
corebalt_backup_drill_backup_timestamp_seconds ${3:-0}
# HELP corebalt_backup_drill_checks_failed Steps and checks that failed in the last drill.
# TYPE corebalt_backup_drill_checks_failed gauge
corebalt_backup_drill_checks_failed ${DRILL_FAILED:-1}
# HELP corebalt_backup_drill_ledger_entries_inconsistent Ledger entries whose balance does not follow from the previous one (B3); -1 when not checked.
# TYPE corebalt_backup_drill_ledger_entries_inconsistent gauge
corebalt_backup_drill_ledger_entries_inconsistent ${DRILL_LEDGER_BAD:--1}
# HELP corebalt_backup_drill_wallets_inconsistent Wallets whose cached balance differs from the ledger (B4); -1 when not checked.
# TYPE corebalt_backup_drill_wallets_inconsistent gauge
corebalt_backup_drill_wallets_inconsistent ${DRILL_WALLET_BAD:--1}
# HELP corebalt_backup_drill_rows Rows per table in the restored database.
# TYPE corebalt_backup_drill_rows gauge
EOF
        echo "${DRILL_ROWS:-}" | awk 'NF == 2 {printf "corebalt_backup_drill_rows{table=\"%s\"} %s\n", $1, $2}'
    } | write_metrics corebalt_backup_drill.prom
}

cmd_stanza() {
    require_deployed
    quiet "stanza present in the repository" pgbr stanza-create || return 1
    quiet "WAL archiving works (a segment pushed and found in the repository)" pgbr check || return 1
}

cmd_backup() {
    require_deployed
    local started rc=0 annotations=() schema t n
    started=$(date +%s)
    echo "== backup $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    quiet "stanza present" pgbr stanza-create || rc=1
    if [ $rc = 0 ]; then
        # Counted before the backup starts, so the backup holds at least this; the drill checks it.
        schema=$(psql_live -c "$schema_sql") || schema=0
        annotations+=("--annotation=schema=$schema")
        while IFS='|' read -r t n; do [ -n "$t" ] && annotations+=("--annotation=rows.$t=$n"); done < <(psql_live -c "$(counts_sql)")
        quiet "full backup, schema $schema, $((${#annotations[@]} - 1)) tables counted" pgbr --type=full "${annotations[@]}" backup || rc=1
    fi
    write_backup_metrics "$started" "$rc"
    if [ $rc = 0 ]; then echo "backupctl: backup ok"; else echo "backupctl: BACKUP FAILED (metrics written; see above)" >&2; fi
    return $rc
}

scratch_reset() { # an empty scratch store: a directory on the data disk, or a volume (the test)
    case "$DRILL_STORE" in
        /*) install -d -m 0700 -o 999 -g 999 "$DRILL_STORE"; find "$DRILL_STORE" -mindepth 1 -delete ;;
        *)  docker volume rm -f "$DRILL_STORE" >/dev/null 2>&1 || true; docker volume create "$DRILL_STORE" >/dev/null ;;
    esac
}
scratch_remove() {
    docker rm -f "$DRILL_NAME" >/dev/null 2>&1 || true
    case "$DRILL_STORE" in
        /*) [ -d "$DRILL_STORE" ] && find "$DRILL_STORE" -mindepth 1 -delete ;;
        *)  docker volume rm -f "$DRILL_STORE" >/dev/null 2>&1 || true ;;
    esac
}
drill_run() { # <label> <image>: restore, start, check; sets DRILL_* for the metrics
    local label=$1 image=$2 net=() i state='' expected schema want t n
    local mounts=(-v "$DRILL_STORE:/var/lib/postgresql/data" -v "$STACK_DIR/pgbackrest.conf:/etc/pgbackrest/pgbackrest.conf:ro")
    [ -n "$DRILL_NETWORK" ] && net=(--network "$DRILL_NETWORK")
    DRILL_ROWS=""; DRILL_LEDGER_BAD=-1; DRILL_WALLET_BAD=-1; DRILL_FAILED=1
    quiet "restore $label into the scratch store" \
        docker run --rm -u postgres "${net[@]}" "${mounts[@]}" "$image" pgbackrest --stanza=main --set="$label" restore || return 1
    # Archiving off and no TCP listener: the scratch must never push WAL into the repository, nor be reached.
    docker run -d --name "$DRILL_NAME" "${net[@]}" "${mounts[@]}" "$image" \
        postgres -c archive_mode=off -c listen_addresses= >/dev/null || return 1
    for ((i = 0; i < DRILL_WAIT; i += 5)); do
        state=$(docker exec -u postgres "$DRILL_NAME" psql -X -Atq -c 'select pg_is_in_recovery()' 2>/dev/null || true)
        [ "$state" = f ] && break
        sleep 5
    done
    if [ "$state" != f ]; then
        echo "   FAIL the scratch instance did not finish recovery within ${DRILL_WAIT}s"
        docker logs --tail 30 "$DRILL_NAME" 2>&1 | sed 's/^/        /'
        return 1
    fi
    echo "   ok   recovery complete (the archived WAL replayed) after ${i}s"
    DRILL_FAILED=0
    expected=$(backup_annotations "$label")
    if [ -z "$expected" ]; then echo "   FAIL $label carries no annotations (not made by backupctl backup)"; DRILL_FAILED=1; fi
    schema=$(psql_drill -c "$schema_sql")
    want=$(echo "$expected" | awk '$1 == "schema" {print $2}')
    if [ -n "$want" ] && [ "$schema" = "$want" ]; then echo "   ok   schema version $schema, as annotated"
    else echo "   FAIL schema version: restored '$schema', annotated '$want'"; DRILL_FAILED=$((DRILL_FAILED + 1)); fi
    while IFS='|' read -r t n; do
        [ -n "$t" ] || continue
        DRILL_ROWS+="$t $n"$'\n'
        want=$(echo "$expected" | awk -v k="rows.$t" '$1 == k {print $2}')
        if [ -n "$want" ] && [ "$n" -ge "$want" ]; then echo "   ok   $t: $n rows (at least the $want counted before the backup)"
        else echo "   FAIL $t: $n rows, '$want' counted before the backup"; DRILL_FAILED=$((DRILL_FAILED + 1)); fi
    done < <(psql_drill -c "$(counts_sql)")
    # B3: every entry's balance follows from the previous one; B4: the cached balance is the last entry's.
    DRILL_LEDGER_BAD=$(psql_drill -c "with e as (select amount_minor, balance_after_minor,
        lag(balance_after_minor, 1, 0) over (partition by wallet_id order by created_at, id) as prev from billing_ledger_entry)
        select count(*) from e where prev + amount_minor <> balance_after_minor")
    DRILL_WALLET_BAD=$(psql_drill -c "select count(*) from billing_wallet w where w.balance_minor <> coalesce((select e.balance_after_minor
        from billing_ledger_entry e where e.tenant_id = w.tenant_id and e.wallet_id = w.id order by e.created_at desc, e.id desc limit 1), 0)")
    if [ "$DRILL_LEDGER_BAD" = 0 ]; then echo "   ok   ledger: every entry's balance follows from the previous one (B3)"
    else echo "   FAIL ledger: $DRILL_LEDGER_BAD entries whose balance does not follow from the previous one (B3)"; DRILL_FAILED=$((DRILL_FAILED + 1)); fi
    if [ "$DRILL_WALLET_BAD" = 0 ]; then echo "   ok   wallets: every cached balance equals its last entry (B4)"
    else echo "   FAIL wallets: $DRILL_WALLET_BAD cached balances differ from the ledger (B4)"; DRILL_FAILED=$((DRILL_FAILED + 1)); fi
    [ "$DRILL_FAILED" = 0 ]
}
cmd_drill() {
    require_deployed
    local started image label stop rc=0
    started=$(date +%s)
    image=$(envval POSTGRES_IMAGE images.env)
    echo "== restore drill $(date -u +%Y-%m-%dT%H:%M:%SZ): the newest backup into a scratch instance ($DRILL_STORE)"
    read -r label stop _ < <(info_summary 2>/dev/null || echo "- 0 0")
    if [ "$label" = - ]; then
        echo "   FAIL no backup in the repository to restore (backupctl backup)"
        write_drill_metrics "$started" 1 0
        return 1
    fi
    trap scratch_remove EXIT
    scratch_reset
    drill_run "$label" "$image" || rc=1
    scratch_remove
    trap - EXIT
    write_drill_metrics "$started" "$rc" "$stop"
    if [ $rc = 0 ]; then echo "backupctl: drill ok ($label, $(( $(date +%s) - started ))s)"
    else echo "backupctl: DRILL FAILED ($label; metrics written; see above)" >&2; fi
    return $rc
}

cmd_restore() {
    require_deployed
    local target="" opts=() out rc=0 entries
    while [ $# -gt 0 ]; do
        case "$1" in
            --target) target=$2; shift 2 ;;
            --target=*) target=${1#--target=}; shift ;;
            *) echo "usage: backupctl restore [--target \"<timestamp, e.g. 2026-10-22 03:15:00+00>\"]" >&2; return 2 ;;
        esac
    done
    if compose ps --status running --services postgres api-1 api-2 tunnel-worker freeradius web 2>/dev/null | grep -q .; then
        echo "backupctl: the stack is running; stop it first (stackctl stop)" >&2; return 1
    fi
    entries=$(compose run --rm --no-deps -T -u postgres postgres sh -c 'ls -A /var/lib/postgresql/data | wc -l' 2>/dev/null | tr -d '[:space:]')
    if [ "$entries" != 0 ]; then
        echo "backupctl: the database directory is not empty ($entries entries); a restore needs an empty one: move it aside first (runbook, Restore in place)" >&2
        return 1
    fi
    [ -n "$target" ] && opts=(--type=time "--target=$target" --target-action=promote)
    echo "== restoring $( [ -n "$target" ] && echo "to $target" || echo "the newest backup, then every archived WAL")"
    if out=$(compose run --rm --no-deps -T -u postgres postgres pgbackrest --stanza=main "${opts[@]}" restore 2>&1); then rc=0; else rc=$?; fi
    echo "$out" | grep -E 'INFO: (restore|repo)|ERROR|WARN' | sed 's/^/   /' || true
    [ $rc = 0 ] || { echo "backupctl: RESTORE FAILED" >&2; return 1; }
    echo "backupctl: restored; start the stack (stackctl up): PostgreSQL replays the archived WAL$( [ -n "$target" ] && echo " up to $target"), promotes, then the services start"
}

cmd_status() {
    [ -f images.env ] || { echo "not deployed"; return 0; }
    [ -s pgbackrest.conf ] || { echo "backups not configured (no pgbackrest.conf)"; return 0; }
    local f
    echo "== repository"
    pgbr info 2>&1 | sed 's/^/   /' || echo "   (pgbackrest info failed: is the stack running? stackctl status)"
    for f in corebalt_backup corebalt_backup_drill; do
        [ -f "$METRICS_DIR/$f.prom" ] || continue
        echo "== $f (metrics)"
        grep -v '^#' "$METRICS_DIR/$f.prom" | sed 's/^/   /'
    done
    [ -f "$METRICS_DIR/corebalt_backup.prom" ] || echo "== no backup has run yet (backupctl backup)"
    [ -f "$METRICS_DIR/corebalt_backup_drill.prom" ] || echo "== no restore drill has run yet (backupctl drill)"
}

case "${1:-}" in
    stanza) cmd_stanza ;;
    backup) cmd_backup ;;
    drill) cmd_drill ;;
    restore) shift; cmd_restore "$@" ;;
    status) cmd_status ;;
    *) sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2 ;;
esac
