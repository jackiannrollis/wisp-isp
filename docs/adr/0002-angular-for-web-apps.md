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

## Amendment 2026-10-20 · Icons

- **Tabler Icons** through the official `@tabler/icons-angular` package (MIT, version pinned) for both apps, in its outline style.
- Icons are imported one by one (`IconRouter`), so only the icons used are bundled and a wrong name fails at compile time. There is no icon CDN and no webfont: the portal must load inside the walled garden on cheap phones.
- Sizes: 20 px in navigation, 24 px on cards, 16 to 18 px in buttons; stroke about 1.75, set once. Icons sit in Material components through their slot attributes (`matPrefix`, `matListItemIcon`). Selected states use Tabler's filled variants, because Tabler has no variable fill axis like Material Symbols.
- Status is never colour alone: online, offline and expiring carry text, and their colours are theme tokens checked for contrast with the tenant's brand colour, light and dark.
- One icon set only; Material components' own internal icons (paginator, datepicker) are inline SVGs and unaffected.
