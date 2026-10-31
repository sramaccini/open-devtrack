# ADR-05: Licence

Status: Accepted (31 October 2026, decided by the maintainer) — decision D5 of the project plan

## Context

The plan proposed Apache 2.0 or AGPL 3.0, «to align with the licence of Quadrava». Quadrava is licensed under the
Mozilla Public License 2.0 with a contributor licence agreement, to stay open while keeping the possibility of
commercial editions or services later.

## Decision

**The same licence and contribution rules as Quadrava**: MPL-2.0 ([LICENSE](../LICENSE)), contributions under the
[CLA](../CLA.md) accepted in the pull request ([CONTRIBUTING.md](../CONTRIBUTING.md)), the name and the logo (the
files in `brand/`, outside the MPL) protected by [TRADEMARKS.md](../TRADEMARKS.md). The repository is github.com/sramaccini/open-devtrack.

## Alternatives considered

- **Apache 2.0.** Permissive, but changes made to OpenDevTrack in closed products would not come back.
- **AGPL 3.0.** Strong copyleft also for network use; it would keep companies from embedding or hosting it, and would
  differ from Quadrava.

## Consequences

- MPL-2.0 is file-level copyleft: whoever changes a file of OpenDevTrack and distributes it publishes that file; their
  own files may stay closed.
- No external contribution is merged without the CLA sentence in its pull request.
- The CLA is a draft, as Quadrava's: a legal review is advised before the first external contribution.
