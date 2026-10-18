# Corebalt ISP Platform — Documentation

Human-facing documentation for the platform. Read in order the first time; afterwards jump to the section you need.

| # | Document | What it answers |
|---|----------|-----------------|
| 01 | [Product overview](01-product-overview.md) | What we are building, for whom, and the one principle behind it |
| 02 | [Architecture and tech stack](02-architecture.md) | Components, stack, module boundaries, events, infrastructure |
| 03 | [Domain model](03-domain-model.md) | Entities, fields, relationships, enumerations |
| 04 | [Subscription lifecycle](04-subscription-lifecycle.md) | States, transitions, how expiry is enforced |
| 05 | [Billing and payments](05-billing-and-payments.md) | Wallet ledger, prepaid and postpaid, M-Pesa integration |
| 06 | [Network access control](06-network-access-control.md) | FreeRADIUS, PPPoE, hotspot, CoA, restricted mode, usage |
| 07 | [Multi-tenancy](07-multi-tenancy.md) | Isolation, usernames, secrets, tenant onboarding |
| 08 | [Reliability, security and compliance](08-reliability-security-compliance.md) | Failure modes, backups, alerts, RBAC, Kenya DPA, eTIMS |
| 09 | [Roadmap](09-roadmap.md) | v1, v2 and v3 scope, the gates between them, v1 build order |
| — | [Architecture decision records](adr/README.md) | Why the big choices were made |

The `context/` folder at the repository root holds the condensed version of all of this for AI coding assistants and new developers: project summary, glossary, domain rules, engineering conventions, decision log and open questions.

## Keeping the documentation current

- A change to a business rule updates the relevant document here **and** `context/domain-rules.md`.
- A significant technical decision gets an ADR in `docs/adr/`, and a row in `context/decisions.md`.
- A resolved question leaves `context/open-questions.md` and lands in the document that now states the answer.
- Diagrams are Mermaid or plain text so they live in git and render on GitHub, GitLab and in most editors.
