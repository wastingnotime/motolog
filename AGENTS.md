# MotoLog repository guidance

## Identity and ownership

MotoLog is WastingNoTime's Android-only, local-first personal motorcycle
companion. This public repository owns its product requirements, domain rules,
Android implementation when authorized, and original reference summaries.
It is independent of Rastro. Read [README.md](README.md), then
[the V1 product brief](docs/product/v1.md) and
[the planning gate](docs/product/planning.md) before changing product behavior.

## Boundaries

- V1 serves one owner motorcycle, a Yamaha Fazer 250 in the 2016/2017 target
  range. Exact model, market and manual applicability still require verification.
- Do not add accounts, a backend dependency, cloud sync, multiple motorcycles,
  workshop/marketplace workflows, rider profiles, a general motorcycle catalog,
  iOS, web, or an MCP application.
- Core registration, recording, history, tracking and dashboard calculations
  must work offline. Owner-entered odometer readings and GPS measurements have
  distinct authority; preserve their sources.
- This setup does not authorize Android feature implementation. First inspect
  the repository, prepare a repository-grounded plan, and obtain owner acceptance
  and direction. Open decisions remain open until explicitly resolved.

## Public and internal surfaces

- `docs/product/` is durable product and domain truth. Confirmed requirements
  are stable intent; proposed defaults and implementation slices are planning.
- `references/` holds only original factual summaries and nonpersonal source
  identity, edition, page/table and hash metadata. Follow its publication rules.
- No runtime, API, integration or data-format contract is exported yet. Add
  `contracts/` only when an actual stable boundary needs documentation.
- Future Android internals are not shared WNT contracts. Do not couple sibling
  repositories to private implementation details.
- WNT tasks own coordination, status and acceptance evidence. Keep technical
  decisions here; record cross-repository ownership or blockers on their tasks.

## Public-content rules

Never commit personal vehicle files, identifiers, real owner records, credentials,
private sibling artifacts, local absolute evidence paths, the owner's manual,
or its screenshots, renders, extracts or copied text. Do not invent manual
intervals or present unverified data as a maintenance baseline. Review the full
staged diff and file list before a public push; ignore rules are only a guard.
Use synthetic, nonpersonal examples if examples are needed.

## Working conventions

Use conventional commit prefixes. Keep repository-specific rules here and shared
WNT toolkit guidance in user space. Resolve shared guidance with
`wnt capability show <name>`; use `mcp-usage` or `project-surfaces` when relevant.
This repository was resolved using `wnt create-repository`. Do not copy toolkit
policy or install repository-local WNT tooling. Repository material is licensed
under MPL-2.0; preserve [LICENSE](LICENSE) and applicable notices.
