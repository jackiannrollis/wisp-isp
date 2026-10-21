#!/usr/bin/env bash
# Linux/macOS twin of ops.ps1 (same commands): Ansible, cosign and crane from the ops image.
#   infra/ops/ops.sh lint | build | shell
#   infra/ops/ops.sh bootstrap|provision|init-disk|unlock <inventory> [--limit host] [--check]
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
IMAGE=corebalt-ops:local
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_ed25519}"
cmd="${1:?usage: ops.sh <lint|build|shell|bootstrap|provision|init-disk|unlock> [inventory] [--limit host] [--check]}"
shift
build() { docker build -t "$IMAGE" -f "$REPO/infra/ops/Dockerfile" "$REPO/infra"; }
[ "$cmd" = build ] && { build; exit 0; }
docker image inspect "$IMAGE" >/dev/null 2>&1 || build

run=(docker run --rm -it -v "$REPO:/repo:ro" -v corebalt-ops-ssh:/home/ops/.ssh/known)
[ -f "$SSH_KEY" ] && run+=(-v "$SSH_KEY:/run/secrets/ssh_key:ro")
for name in OPS_GHCR_USER OPS_GHCR_TOKEN; do [ -n "${!name:-}" ] && run+=(-e "$name"); done
run+=("$IMAGE")

playbook() {
    local book=$1 inventory=${2:?name the inventory, e.g. staging}; shift 2
    [ -d "$REPO/infra/ansible/inventories/$inventory" ] || { echo "no inventory $inventory" >&2; exit 1; }
    local extra=() limit=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --limit) limit=$2; extra+=(--limit "$2"); shift 2 ;;
            --check) extra+=(--check --diff); shift ;;
            *) echo "unknown option $1" >&2; exit 2 ;;
        esac
    done
    if [ "$book" != site.yml ] || [ "$cmd" = bootstrap ]; then
        [ -n "$limit" ] || { echo "$cmd one host at a time: --limit <host>" >&2; exit 2; }
    fi
    [ "$cmd" = bootstrap ] && extra+=(-e ansible_user=root)
    "${run[@]}" ansible-playbook -i "inventories/$inventory" "$book" "${extra[@]}"
}

case "$cmd" in
    lint) "${run[@]}" sh -c "ansible-lint && ansible-playbook -i inventories/example site.yml data-volume-init.yml unlock.yml --syntax-check" ;;
    shell) "${run[@]}" bash ;;
    bootstrap|provision) playbook site.yml "$@" ;;
    init-disk) playbook data-volume-init.yml "$@" ;;
    unlock) playbook unlock.yml "$@" ;;
    *) echo "unknown command $cmd" >&2; exit 2 ;;
esac
