# ADR 0002 · Angular for the admin dashboard and customer portal

**Status:** Accepted
**Date:** 2026-10-17

## Context

The admin dashboard will grow into a large internal application: customers, subscriptions, packages, billing, network, hotspot, support, reports and administration, with permission-dependent screens and many forms. The customer portal is small. Neither needs search-engine indexing. Next.js and Angular were both viable; Corebalt has shipped Next.js before.

## Decision

Use Angular 22 with TypeScript, Signals, reactive forms, Angular Material or PrimeNG, and ECharts for both the admin dashboard and the customer portal. The public marketing site is a separate concern and may use Angular with server-side rendering or a static site generator.

## Consequences

- Angular's opinionated structure (components, services, guards, interceptors, dependency injection) keeps a large application consistent as more developers join.
- Clean separation: the browser talks to the Spring Boot REST and WebSocket API; there is no second server layer to run.
- Reactive forms and route guards fit permission-heavy, form-heavy screens.
- The hotspot captive portal (v2) stays plain HTML; it must load instantly on cheap phones inside the walled garden.
- Shared components with any earlier Next.js work are not reusable; accepted.
