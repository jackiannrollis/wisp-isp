#!/usr/bin/env bash
# Linux/macOS twin of ops.ps1 (same commands): Ansible, cosign and crane from the ops image.
#   infra/ops/ops.sh lint | build | shell
#   infra/ops/ops.sh secrets <inventory>
#   infra/ops/ops.sh bootstrap|provision|init-disk|unlock <inventory> [--limit host] [--check]
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
IMAGE=corebalt-ops:local
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_ed25519}"
cmd="${1:?usage: ops.sh <lint|build|shell|secrets|bootstrap|provision|init-disk|unlock> [inventory] [--limit host] [--check]}"
shift
build() { docker build -t "$IMAGE" -f "$REPO/infra/ops/Dockerfile" "$REPO/infra"; }
[ "$cmd" = build ] && { build; exit 0; }
docker image inspect "$IMAGE" >/dev/null 2>&1 || build

inventories="$REPO/infra/ansible/inventories"
run=(docker run --rm -it -v "$REPO:/repo:ro" -v "$inventories:/repo/infra/ansible/inventories" -v corebalt-ops-ssh:/home/ops/.ssh/known)
[ -f "$SSH_KEY" ] && run+=(-v "$SSH_KEY:/run/secrets/ssh_key:ro")
run+=("$IMAGE")

require_inventory() {
    local inventory=${1:?name the inventory, e.g. staging}
    [ -d "$inventories/$inventory" ] || { echo "no inventory at infra/ansible/inventories/$inventory" >&2; exit 1; }
}

playbook() {
    local book=$1 inventory=${2:?name the inventory, e.g. staging}; shift 2
    require_inventory "$inventory"
    local extra=() limit=""
    local vault="$inventories/$inventory/group_vars/all/vault.yml"
    [ -f "$vault" ] && head -c 14 "$vault" | grep -q '^\$ANSIBLE_VAULT' && extra+=(--ask-vault-pass)
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
    secrets) require_inventory "${1:-}"; "${run[@]}" bash /repo/infra/ops/secrets-init.sh "$1" ;;
    bootstrap|provision) playbook site.yml "$@" ;;
    init-disk) playbook data-volume-init.yml "$@" ;;
    unlock) playbook unlock.yml "$@" ;;
    *) echo "unknown command $cmd" >&2; exit 2 ;;
esac
