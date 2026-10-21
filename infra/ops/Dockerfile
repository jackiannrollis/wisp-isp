# The operator's toolbox (decisions.md 2026-10-21): Ansible, cosign and crane in one image, so a
# Windows machine needs only Docker. Built from infra/ (context) by infra/ops/ops.ps1 or ops.sh.
FROM ghcr.io/sigstore/cosign/cosign:v3.1.4 AS cosign
FROM gcr.io/go-containerregistry/crane:v0.22.1 AS crane

FROM python:3.13.16-slim-trixie
RUN apt-get update \
    && apt-get install -y --no-install-recommends openssh-client ca-certificates rsync \
    && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir ansible-core==2.21.5 ansible-lint==26.10.1 netaddr==1.3.0
COPY ansible/requirements.yml /tmp/requirements.yml
RUN ansible-galaxy collection install -r /tmp/requirements.yml -p /usr/share/ansible/collections \
    && rm /tmp/requirements.yml
COPY --from=cosign /ko-app/cosign /usr/local/bin/cosign
COPY --from=crane /ko-app/crane /usr/local/bin/crane
COPY ops/entrypoint.sh /usr/local/bin/ops-entrypoint
RUN chmod 0755 /usr/local/bin/ops-entrypoint \
    && useradd --create-home --uid 1000 ops
# A Windows bind mount looks world-writable, so Ansible would skip a cfg found in the working
# directory; naming it explicitly is honoured.
ENV ANSIBLE_CONFIG=/repo/infra/ansible/ansible.cfg
USER ops
WORKDIR /repo/infra/ansible
ENTRYPOINT ["/usr/local/bin/ops-entrypoint"]
