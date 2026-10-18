# context/

Condensed, always-current project context for AI coding assistants and for anyone joining the team. Everything here is short enough to load whole at the start of a task; the long explanations live in `docs/`.

| File | Load it when |
|---|---|
| [project.md](project.md) | Starting any task: what the product is, the stack, the modules, the core flows |
| [domain-rules.md](domain-rules.md) | Touching subscriptions, billing, payments or network access: the invariants code must never break |
| [glossary.md](glossary.md) | A term is unfamiliar: ISP, RADIUS, M-Pesa and platform vocabulary |
| [conventions.md](conventions.md) | Writing or reviewing code: backend, frontend, API, database and git conventions |
| [decisions.md](decisions.md) | Choosing a technology or pattern: what has already been decided and where it is explained |
| [open-questions.md](open-questions.md) | Something seems undecided: check here before inventing an answer |

## How to use it with an AI assistant

Point the assistant's project instruction file at this folder, for example by importing `context/project.md`, `context/domain-rules.md` and `context/conventions.md` at the top of your `CLAUDE.md` or equivalent, and load the others on demand. When a task changes a rule, decision or convention, update the matching file here in the same change.

## Keeping it current

- `project.md` and `glossary.md` change rarely; update them when a module or term is added.
- `domain-rules.md` changes with any business rule; the corresponding `docs/` page changes too.
- `decisions.md` gets a row for every new ADR in `docs/adr/`.
- `open-questions.md` only shrinks when a question is answered and only grows when a real decision is pending.
