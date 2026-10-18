# FreeRADIUS 3.2 with rlm_sql reading the platform's PostgreSQL views (ADR 0004).
FROM freeradius/freeradius-server:3.2.10

COPY raddb/mods-available/sql /etc/freeradius/mods-available/sql
COPY raddb/sites-available/corebalt /etc/freeradius/sites-available/corebalt
COPY raddb/clients.conf /etc/freeradius/clients.conf
COPY scripts/radtest.sh /usr/local/bin/corebalt-radtest

# Replace the stock default site with ours; keep inner-tunnel, which the eap module references.
RUN rm -f /etc/freeradius/sites-enabled/default \
    && ln -s ../sites-available/corebalt /etc/freeradius/sites-enabled/corebalt \
    && ln -s ../mods-available/sql /etc/freeradius/mods-enabled/sql \
    && chmod 0755 /usr/local/bin/corebalt-radtest \
    && chown -h freerad:freerad /etc/freeradius/sites-enabled/corebalt /etc/freeradius/mods-enabled/sql

EXPOSE 1812/udp 1813/udp

# FreeRADIUS drops to freerad anyway (radiusd.conf); run the container as that user too.
USER freerad
