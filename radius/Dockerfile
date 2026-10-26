# FreeRADIUS 3.2 with rlm_sql reading the platform's PostgreSQL views (ADR 0004).
FROM freeradius/freeradius-server:3.2.10

# Pick up Ubuntu security fixes newer than the upstream image (the CI image scan gates on them).
RUN apt-get update \
    && apt-get install -y --no-install-recommends --only-upgrade libssl3 openssl \
    && apt-get upgrade -y --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*

COPY raddb/mods-available/sql /etc/freeradius/mods-available/sql
COPY raddb/sites-available/corebalt /etc/freeradius/sites-available/corebalt
COPY raddb/sites-available/corebalt-status /etc/freeradius/sites-available/corebalt-status
COPY raddb/clients.conf /etc/freeradius/clients.conf
COPY scripts/radtest.sh /usr/local/bin/corebalt-radtest

# Replace the stock default site with ours; keep inner-tunnel, which the eap module references. The
# status site answers the monitoring probe on the loopback (infra/monitoring/probes.sh). Every login is
# logged ("Login OK: [CB000001] ..." / "Login incorrect (...): [CB000006] ..."), which the per-tenant
# reject-rate alert reads from Loki (docs/08); passwords stay out of the log (auth_badpass and
# auth_goodpass remain off, A3).
RUN rm -f /etc/freeradius/sites-enabled/default \
    && ln -s ../sites-available/corebalt /etc/freeradius/sites-enabled/corebalt \
    && ln -s ../sites-available/corebalt-status /etc/freeradius/sites-enabled/corebalt-status \
    && ln -s ../mods-available/sql /etc/freeradius/mods-enabled/sql \
    && sed -i -E '0,/^(\s*)auth = no/s//\1auth = yes/' /etc/freeradius/radiusd.conf \
    && grep -Eq '^\s*auth = yes' /etc/freeradius/radiusd.conf \
    && grep -Eq '^\s*auth_badpass = no' /etc/freeradius/radiusd.conf \
    && chmod 0755 /usr/local/bin/corebalt-radtest \
    && chown -h freerad:freerad /etc/freeradius/sites-enabled/corebalt /etc/freeradius/sites-enabled/corebalt-status \
         /etc/freeradius/mods-enabled/sql

# The status site's client secret comes from the environment: RADIUS_STATUS_SECRET, set by every
# compose file that runs this image (a random value per host from the stack's .env).

EXPOSE 1812/udp 1813/udp

# FreeRADIUS drops to freerad anyway (radiusd.conf); run the container as that user too.
USER freerad
