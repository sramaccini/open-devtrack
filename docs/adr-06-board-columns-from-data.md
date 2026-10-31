# ADR-06: Board columns from data

Status: Accepted (31 October 2026) — decision D6 of the project plan

## Context

Boards are configuration in the database (`odt_agile.board`, `board_column`): each team maps its statuses to columns.
With fixed columns in the JSON, every board needs its own screen. The plan recommended columns from a data source, as
an evolution of the Quadrava `kanban`.

That evolution is done (Quadrava K5, commit `8e85497`, in 0.1.0): `columns` may be an expression and `columnsFrom` a
data source. K1–K4 are done too: order inside a column with the neighbours in `onMove`, swimlanes, WIP limits, rich
cards (Quadrava component catalogue § 2.24).

## Decision

**One generic `board` screen for every board: columns from a data source (`columnsFrom`), cards from another.**

```
GET /api/boards/7/columns → [{ "value": "in-progress", "label": "In progress", "limit": 5 }, …]
GET /api/boards/7/cards   → [{ "id": "…", "column": "in-progress", "title": "ODT-42 · SSO login", … }]
```

- The back end answers the columns of the board (with their WIP limit) and the cards with `title`, `subtitle`, badges
  and `row._perm` already computed: the screen has no logic of its own.
- A move calls `POST /api/issues/{key}/transitions` (status) and, when the order changes, the rank with the neighbours
  (`beforeId` / `afterId`); the guards of the workflow decide on the server.

## Alternatives considered

- **Fixed columns per screen.** One screen per board, rewritten when a team changes its workflow: it does not scale
  with configurable boards.

## Consequences

- The temporary solutions of the plan for K1–K5 are no longer needed: F3 adopts the `kanban` of the Quadrava version
  pinned in ADR-01.
- The exact names of the properties are those of Quadrava 0.1.0; the board API of OpenDevTrack follows them.
