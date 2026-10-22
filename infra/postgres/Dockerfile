# PostgreSQL 17 for staging and production (infra/stack/compose.yml): the official image plus
# pgBackRest from the PGDG repository the image already trusts, so WAL archiving, the nightly backup,
# the monthly restore drill and disaster recovery run inside the database container as the postgres
# user (docs/08; infra/ops/runbooks/backups.md). Part of every release like the application images,
# resolved by digest on deploy; the local development stack keeps the plain official image.
# pgBackRest is not version-pinned on purpose: PGDG keeps only its newest build, so a pin would break
# the build at every upstream release. The version is printed at build time.
FROM postgres:17.11
RUN apt-get update \
    && apt-get install -y --no-install-recommends pgbackrest \
    && rm -rf /var/lib/apt/lists/* \
    && pgbackrest version
