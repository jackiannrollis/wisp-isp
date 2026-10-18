# ADR 0003 · RabbitMQ for events and background work, fed by a transactional outbox

**Status:** Accepted
**Date:** 2026-10-17

## Context

The core of the product is an event chain: payment received, wallet credited, subscription renewed, network access changed, session reauthorised, customer notified. Each step can fail independently (the router is unreachable, the SMS provider times out) and must retry without blocking the others. A Postgres-backed job scheduler would have covered v1 with one less service to run, but the platform will add independent workers, and possibly a Go service, that need the same events.

## Decision

Run RabbitMQ from day one as a two-node cluster with durable quorum queues and persistent messages. Business changes and their events are written in the same database transaction through a transactional outbox table; a relay publishes outbox rows to RabbitMQ. Consumers are idempotent (event UUIDs recorded per consumer), retry three times with backoff, then dead-letter with an alert. Redis is used only for cache and rate limits.

## Consequences

- No event is lost between a database commit and the broker, and no event is published for a transaction that rolled back.
- Payment processing, CoA pushes, usage roll-ups and SMS run as independent workers with their own retries.
- A broker outage delays restores and notifications but never logins, because logins go through RADIUS and the database.
- One more clustered service to operate and monitor; queue depth and dead-letter alerts are part of the v1 observability set.
- Routing keys carry an event version so consumers can be upgraded independently.
