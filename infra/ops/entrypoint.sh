#!/bin/sh
# Prepares SSH inside the ops container, then runs the command.
# - The operator's private key is mounted read-only at /run/secrets/ssh_key. A Windows bind mount shows
#   it as world-readable, which ssh refuses, so it is copied into the container's home with mode 0600.
#   The copy disappears with the container (docker run --rm).
# - known_hosts lives on the named volume corebalt-ops-ssh, so a host key is trusted on first use and
#   checked on every later connection (StrictHostKeyChecking=accept-new).
set -eu
mkdir -p "$HOME/.ssh" "$HOME/.ssh/known"
chmod 0700 "$HOME/.ssh"
if [ -f /run/secrets/ssh_key ]; then
    cp /run/secrets/ssh_key "$HOME/.ssh/id_operator"
    chmod 0600 "$HOME/.ssh/id_operator"
fi
cat > "$HOME/.ssh/config" <<EOF
Host *
    IdentityFile $HOME/.ssh/id_operator
    IdentitiesOnly yes
    UserKnownHostsFile $HOME/.ssh/known/known_hosts
    StrictHostKeyChecking accept-new
    ServerAliveInterval 30
EOF
chmod 0600 "$HOME/.ssh/config"
exec "$@"
