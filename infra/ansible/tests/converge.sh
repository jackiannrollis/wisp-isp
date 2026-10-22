#!/usr/bin/env bash
# Converge test for the playbooks (decisions.md 2026-10-21): a throwaway Debian 13 systemd container
# stands in for a fresh server, a loop file for its data disk. Runs what the operator runs, in order:
# bootstrap as root, init-disk, provision as the operator, a reboot-style lock and ops unlock, and a
# second provision that must change nothing. Then checks the hardening from the outside.
# Needs Docker (privileged containers) and the ops image (infra/ops/ops.sh build). Everything it
# creates is removed at the end; the key and passphrase are throwaway fakes. The target shares the
# Docker host's /dev, so device-mapper nodes appear as on a real server (a container's own /dev gets none).
set -euo pipefail
export MSYS_NO_PATHCONV=1
# Git Bash on Windows: Docker needs D:/... rather than /d/..., so take pwd -W where it exists.
REPO="$(cd "$(dirname "$0")/../../.." && (pwd -W 2>/dev/null || pwd))"
NET=corebalt-converge
TARGET=corebalt-converge-target
KEYS=corebalt-converge-keys
OPS_IMAGE=${OPS_IMAGE:-corebalt-ops:local}
PASSPHRASE=converge-test-passphrase-not-a-secret

# The target shares the Docker host's /dev, so undo exactly what this test created there: unmount,
# close the mapping, drop its /dev/mapper link, detach only its own loop file.
cleanup() {
    docker exec "$TARGET" sh -c 'umount /srv/corebalt 2>/dev/null; cryptsetup close corebalt-data 2>/dev/null
        rm -f /dev/mapper/corebalt-data
        for l in $(losetup -j /var/lib/data-disk.img -O NAME -n 2>/dev/null); do losetup -d "$l"; done'         >/dev/null 2>&1 || true
    docker rm -f "$TARGET" >/dev/null 2>&1 || true
    docker volume rm -f "$KEYS" >/dev/null 2>&1 || true
    docker network rm "$NET" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

docker network create --subnet 172.31.250.0/24 "$NET" >/dev/null
docker build -q -t corebalt-converge-target -f "$REPO/infra/ansible/tests/Dockerfile.target" "$REPO/infra/ansible/tests" >/dev/null
docker run -d --name "$TARGET" --hostname target --privileged --cgroupns=host \
    -v /sys/fs/cgroup:/sys/fs/cgroup:rw --tmpfs /run --tmpfs /run/lock     -v /dev:/dev \
    --network "$NET" --ip 172.31.250.10 corebalt-converge-target >/dev/null

# A throwaway operator key in a named volume (no bind mount of a new folder).
docker volume create "$KEYS" >/dev/null
docker run --rm -u 0 -v "$KEYS:/keys" --entrypoint sh "$OPS_IMAGE" -c '
    ssh-keygen -q -t ed25519 -N "" -C converge -f /keys/ssh_key
    printf "operator_ssh_public_keys:\n  - \"%s\"\n" "$(cat /keys/ssh_key.pub)" > /keys/vars.yml
    chown -R 1000 /keys'
docker run --rm -v "$KEYS:/keys" --entrypoint cat "$OPS_IMAGE" /keys/ssh_key.pub \
    | docker exec -i "$TARGET" sh -c 'cat > /root/.ssh/authorized_keys && chmod 600 /root/.ssh/authorized_keys'

for _ in $(seq 1 30); do docker exec "$TARGET" systemctl is-active ssh >/dev/null 2>&1 && break; sleep 1; done
DISK=$(docker exec "$TARGET" sh -c 'truncate -s 300M /var/lib/data-disk.img && losetup -f --show /var/lib/data-disk.img')
echo "== target up; data disk $DISK"

ops_raw() {
    docker run --rm -i --network "$NET" --ip 172.31.250.20 \
        -v "$REPO:/repo:ro" -v "$KEYS:/run/secrets:ro" "$OPS_IMAGE" \
        ansible-playbook -i tests/inventory.yml -e @/run/secrets/vars.yml -e "data_volume_device=$DISK" "$@"
}
# The recap on success; the whole run when a step fails.
ops() {
    local out
    if ! out=$(ops_raw "$@" 2>&1); then echo "$out" | grep -v 'DEPRECATION\|^Origin\|^ *[0-9]* \|^ *^\|ansible_facts\[' | tail -60; echo "FAIL: $*"; return 1; fi
    echo "$out" | tail -3
}

echo "== 1. bootstrap as root (data disk not encrypted yet: skipped with a message)"
ops site.yml -e ansible_user=root

echo "== 2. init-disk"
ops data-volume-init.yml -e "confirm_device=$DISK" -e "luks_passphrase=$PASSPHRASE"

echo "== 3. provision as the operator"
ops site.yml

echo "== 4. lock as after a reboot, then unlock"
docker exec "$TARGET" sh -c 'umount /srv/corebalt 2>/dev/null; cryptsetup close corebalt-data'
# Locked: the stack unit is skipped, not failed, and nothing can be created under the mount point.
docker exec "$TARGET" systemctl start corebalt-stack     || { docker exec "$TARGET" sh -c 'systemctl status corebalt-stack --no-pager; journalctl -u corebalt-stack --no-pager | tail -20'; echo "FAIL: starting the stack unit while locked must be a skip, not an error"; exit 1; }
state=$(docker exec "$TARGET" systemctl is-active corebalt-stack || true)
[ "$state" = inactive ] || { echo "FAIL: the stack unit is '$state' while the disk is locked"; exit 1; }
if docker exec "$TARGET" sh -c 'lsattr -d /srv/corebalt 2>/dev/null | cut -d" " -f1 | grep -q i'; then
    docker exec "$TARGET" mkdir /srv/corebalt/postgres 2>/dev/null && { echo "FAIL: the locked mount point is writable"; exit 1; }
    echo "   locked: stack unit skipped, mount point immutable"
else
    echo "   locked: stack unit skipped (immutable flag unsupported on this container's overlay root; ext4 hosts have it)"
fi
ops unlock.yml -e "luks_passphrase=$PASSPHRASE"
docker exec "$TARGET" findmnt -n -o SOURCE /srv/corebalt | grep -q '/dev/mapper/corebalt-data'

echo "== 5. provision again: nothing may change"
second=$(ops_raw site.yml 2>&1)
echo "$second" | tail -3
if ! echo "$second" | grep -Eq 'changed=0 .*failed=0'; then
    echo "$second" | grep -B3 '^changed:'
    echo "FAIL: second provision changed something"; exit 1
fi

echo "== 6. hardening, seen from outside"
docker exec "$TARGET" sshd -T | grep -E '^(permitrootlogin|passwordauthentication|kbdinteractiveauthentication|allowusers) '
docker exec "$TARGET" ufw status verbose | sed -n '1,20p'
docker exec "$TARGET" grep -h 'Automatic-Reboot "' /etc/apt/apt.conf.d/52corebalt-unattended-upgrades
docker exec "$TARGET" cryptsetup luksDump "$DISK" | grep -E '^Version|PBKDF' | head -2
docker exec "$TARGET" systemctl is-active corebalt-wg-hub docker
docker exec "$TARGET" sh -c 'test -f /srv/corebalt/stack/compose.yml && test "$(stat -c %a /srv/corebalt/stack/.env)" = 600 && grep -q "^ISP_MASTER_KEY=" /srv/corebalt/stack/.env && echo "   stack installed, .env root-only"'
docker exec "$TARGET" systemctl is-enabled corebalt-stack
root_login=$(docker run --rm --network "$NET" -v "$KEYS:/run/secrets:ro" --entrypoint sh "$OPS_IMAGE" -c \
    'cp /run/secrets/ssh_key /tmp/k && chmod 600 /tmp/k && ssh -i /tmp/k -o BatchMode=yes -o StrictHostKeyChecking=no root@172.31.250.10 true 2>&1; echo "exit $?"' | tail -1)
[ "$root_login" != "exit 0" ] || { echo "FAIL: root can still log in"; exit 1; }
echo "   root login refused ($root_login)"
echo "CONVERGE PASSED"
