-- Boards, backlog and sprints (module agile, ADR-15). Projects and issues are only identifiers: no foreign key to
-- another module's schema.

-- Sprints of a project: planned, then active (at most one per project), then completed.
CREATE TABLE odt_agile.sprint (
    id           uuid          PRIMARY KEY DEFAULT uuidv7(),
    project_id   uuid          NOT NULL,
    name         varchar(100)  NOT NULL,
    goal         varchar(1000),
    state        varchar(20)   NOT NULL CHECK (state IN ('planned', 'active', 'completed')),
    starts_on    date,
    ends_on      date,
    created_at   timestamptz   NOT NULL,
    started_at   timestamptz,
    completed_at timestamptz,
    version      bigint        NOT NULL DEFAULT 0,
    CHECK (ends_on IS NULL OR starts_on IS NULL OR ends_on >= starts_on)
);

CREATE INDEX ix_sprint_project ON odt_agile.sprint (project_id, created_at);
CREATE UNIQUE INDEX ux_sprint_active ON odt_agile.sprint (project_id) WHERE state = 'active';

-- The plan of each issue: its place in the one order of the project (a fractional index in base 62, compared byte by
-- byte), the sprint not completed it is in (none: the backlog), its story points.
CREATE TABLE odt_agile.issue_plan (
    issue_id   uuid         PRIMARY KEY,
    project_id uuid         NOT NULL,
    rank       varchar(200) COLLATE "C" NOT NULL CHECK (rank ~ '^[0-9A-Za-z]{6,}$'),
    sprint_id  uuid         REFERENCES odt_agile.sprint (id),
    points     int          CHECK (points BETWEEN 0 AND 999),
    UNIQUE (project_id, rank)
);

CREATE INDEX ix_issue_plan_sprint ON odt_agile.issue_plan (sprint_id);

-- What a sprint held from its start: when an issue came in and went out, and how (done at the completion, carried to
-- another sprint or to the backlog, or removed before), with its points then. For the reports (F4).
CREATE TABLE odt_agile.sprint_issue (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    sprint_id  uuid        NOT NULL REFERENCES odt_agile.sprint (id) ON DELETE CASCADE,
    issue_id   uuid        NOT NULL,
    points     int,
    added_at   timestamptz NOT NULL,
    removed_at timestamptz,
    outcome    varchar(20) CHECK (outcome IN ('done', 'carried', 'removed'))
);

CREATE INDEX ix_sprint_issue ON odt_agile.sprint_issue (sprint_id, issue_id);

-- Boards of a project: scrum shows the active sprint, kanban every issue. Without columns of its own a board has one
-- column per status of the workflow.
CREATE TABLE odt_agile.board (
    id         uuid         PRIMARY KEY DEFAULT uuidv7(),
    project_id uuid         NOT NULL,
    name       varchar(100) NOT NULL,
    kind       varchar(20)  NOT NULL CHECK (kind IN ('scrum', 'kanban')),
    lane       varchar(20)  NOT NULL CHECK (lane IN ('none', 'assignee', 'priority', 'type')),
    created_at timestamptz  NOT NULL,
    version    bigint       NOT NULL DEFAULT 0
);

CREATE INDEX ix_board_project ON odt_agile.board (project_id, created_at);

CREATE TABLE odt_agile.board_column (
    board_id  uuid         NOT NULL REFERENCES odt_agile.board (id) ON DELETE CASCADE,
    position  int          NOT NULL,
    name      varchar(100) NOT NULL,
    statuses  text[]       NOT NULL,
    wip_limit int          CHECK (wip_limit BETWEEN 1 AND 999),
    PRIMARY KEY (board_id, position)
);

-- The projects and issues created before this module: a board each, and the issues in the order of their numbers.
INSERT INTO odt_agile.board (project_id, name, kind, lane, created_at)
SELECT id, 'Board', 'kanban', 'none', now() FROM odt_project.project;

INSERT INTO odt_agile.issue_plan (issue_id, project_id, rank)
SELECT id, project_id, lpad(row_number() OVER (PARTITION BY project_id ORDER BY number)::text, 6, '0')
FROM odt_issue.issue;
