# ADR 0001 · One Spring Boot modular monolith, not microservices

**Status:** Accepted
**Date:** 2026-10-17

## Context

The platform is business-logic heavy: customers, packages, subscriptions, ledgers, payments, entitlements, notifications, roles and audit. A small team will build and operate it, and every tenant ISP depends on it being up. Go was considered for the backend and microservices for the shape.

## Decision

Build the backend as one deployable Spring Boot application on Java 25 LTS, organised into strict modules with Spring Modulith (`tenancy`, `identity`, `customers`, `packages`, `subscriptions`, `billing`, `payments`, `network`, `notifications`, `reporting`, later `hotspot` and `support`). Modules communicate through published events or small explicit interfaces, never through each other's entities or repositories, and a test verifies the boundaries.

Go is deferred: a separate Go service for telemetry or accounting ingest may be added later, consuming the same events, if router polling or accounting volume outgrows the monolith.

## Consequences

- One codebase, one deployment, one set of transactions across modules; renewals, ledger writes and entitlement changes commit together.
- Spring's ecosystem covers security, RBAC, JPA, validation, scheduling and OpenAPI without custom plumbing.
- Module boundaries make a later extraction (for example the network module into Go) a known cut rather than a rewrite.
- Memory and startup cost are higher than Go; acceptable for a service that runs continuously on two instances.
- Discipline is required: cross-module shortcuts are easy in a monolith and the Modulith verification test is what prevents them.
