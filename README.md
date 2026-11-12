# MotoLog

MotoLog is WastingNoTime's Android-only, local-first personal motorcycle companion.
V1 helps one owner record fuel and service, track rides, and see what needs
attention for one Yamaha Fazer 250 in the 2016/2017 target range. Exact model,
market and manual applicability must be verified before maintenance data is seeded.

This repository currently contains product guidance and references policy only.
There is no installable app, Android module, build or app test suite yet.
App implementation requires an accepted repository-grounded plan and owner direction.

## V1 scope

- Offline motorcycle registration and editable maintenance templates with provenance.
- Preventive, programmed and corrective maintenance, with pending work,
  postponement and a read-only history of completed work.
- Refueling through either liters and total paid or total paid and unit price.
- Manual ride tracking, optional automatic tracking, and a dashboard with explicit
  missing-data states and large quick controls for use while safely stopped.

V1 excludes accounts, rider profiles, multiple motorcycles, a general motorcycle
catalog, workshops, marketplaces, cloud sync, backend dependencies, iOS and web.
MotoLog is independent of Rastro. No backend, web or MCP application belongs in
this Android-only setup.

## Read next

- [Product requirements and invariants](docs/product/v1.md)
- [Open decisions, planning gate and verification slices](docs/product/planning.md)
- [Inspected Android surfaces and separate bootstrap proposal](docs/product/implementation-surfaces.md)
- [Source provenance and publication rules](references/README.md)
- [Contributor and agent guidance](AGENTS.md)

Repository docs own technical truth; WNT tasks track coordination and acceptance.
Personal vehicle records and source manuals stay outside this public repository.

## License

SPDX-License-Identifier: MPL-2.0

Repository material is subject to the Mozilla Public License, v. 2.0.
See the complete [LICENSE](LICENSE), published by
[Mozilla](https://www.mozilla.org/MPL/2.0/).
