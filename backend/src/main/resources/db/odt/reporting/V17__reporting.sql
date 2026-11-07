-- Read model of the reports and dashboards (ADR-17), written after the commit from the events of issue and agile.
-- Days are in the zone of the instance (odt.reporting.zone).

-- Each issue as the reports see it: started_on is the first day in an in_progress status, resolved_on the day it
-- entered done (cleared when it leaves it).
CREATE TABLE odt_reporting.issue_state (
    issue_id    uuid         PRIMARY KEY,
    tenant_id   varchar(50)  NOT NULL,
    project_id  uuid         NOT NULL,
    type        varchar(20)  NOT NULL,
    priority    varchar(20)  NOT NULL,
    status      varchar(50)  NOT NULL,
    category    varchar(20)  NOT NULL CHECK (category IN ('todo', 'in_progress', 'done')),
    assignee    varchar(150),
    points      int,
    sprint_id   uuid,
    created_on  date         NOT NULL,
    started_on  date,
    resolved_on date
);

CREATE INDEX ix_issue_state_project ON odt_reporting.issue_state (project_id, category);
CREATE INDEX ix_issue_state_sprint ON odt_reporting.issue_state (sprint_id) WHERE sprint_id IS NOT NULL;

-- The issues of a project (sprint_id null) or of its active sprint per category, and the points not done, at the end
-- of each day: written again at each event of the day and every night.
CREATE TABLE odt_reporting.daily_snapshot (
    project_id       uuid NOT NULL,
    sprint_id        uuid,
    day              date NOT NULL,
    todo             int  NOT NULL,
    in_progress      int  NOT NULL,
    done             int  NOT NULL,
    remaining_points int  NOT NULL,
    CONSTRAINT uq_daily_snapshot UNIQUE NULLS NOT DISTINCT (project_id, sprint_id, day)
);

-- Each sprint from its start: the points committed then, done and carried at the completion.
CREATE TABLE odt_reporting.sprint_result (
    sprint_id        uuid         PRIMARY KEY,
    project_id       uuid         NOT NULL,
    name             varchar(100) NOT NULL,
    starts_on        date         NOT NULL,
    ends_on          date         NOT NULL,
    committed_points int          NOT NULL,
    done_points      int,
    carried_points   int,
    completed_at     timestamptz
);

CREATE INDEX ix_sprint_result_project ON odt_reporting.sprint_result (project_id, completed_at);

-- From the existing data. The days of the status changes come from the changelog of audit (used only here).
INSERT INTO odt_reporting.issue_state (issue_id, tenant_id, project_id, type, priority, status, category, assignee,
                                       points, sprint_id, created_on, started_on, resolved_on)
WITH category AS (
    SELECT w.project_id, s.code, s.category
    FROM odt_workflow.workflow w
    JOIN odt_workflow.status s ON s.workflow_id = w.id
), moves AS (
    SELECT c.issue_id, (c.changed_at AT TIME ZONE '${reporting_zone}')::date AS day, k.category
    FROM odt_audit.issue_change c
    JOIN odt_issue.issue i ON i.id = c.issue_id
    JOIN category k ON k.project_id = i.project_id AND k.code = c.new_value
    WHERE c.field = 'status'
)
SELECT i.id, i.tenant_id, i.project_id, lower(i.type), lower(i.priority), i.status, coalesce(k.category, 'todo'),
       i.assignee, pl.points, pl.sprint_id, (i.created_at AT TIME ZONE '${reporting_zone}')::date,
       CASE WHEN k.category IN ('in_progress', 'done')
            THEN (SELECT min(m.day) FROM moves m WHERE m.issue_id = i.id AND m.category = 'in_progress') END,
       CASE WHEN k.category = 'done'
            THEN coalesce((SELECT max(m.day) FROM moves m WHERE m.issue_id = i.id AND m.category = 'done'),
                          (i.updated_at AT TIME ZONE '${reporting_zone}')::date) END
FROM odt_issue.issue i
LEFT JOIN category k ON k.project_id = i.project_id AND k.code = i.status
LEFT JOIN odt_agile.issue_plan pl ON pl.issue_id = i.id;

INSERT INTO odt_reporting.sprint_result (sprint_id, project_id, name, starts_on, ends_on, committed_points, done_points,
                                         carried_points, completed_at)
SELECT s.id, s.project_id, s.name, coalesce(s.starts_on, (s.started_at AT TIME ZONE '${reporting_zone}')::date),
       coalesce(s.ends_on, s.starts_on, (s.started_at AT TIME ZONE '${reporting_zone}')::date),
       coalesce((SELECT sum(h.points) FROM odt_agile.sprint_issue h WHERE h.sprint_id = s.id AND h.added_at = s.started_at), 0),
       CASE WHEN s.state = 'completed'
            THEN coalesce((SELECT sum(h.points) FROM odt_agile.sprint_issue h WHERE h.sprint_id = s.id AND h.outcome = 'done'), 0) END,
       CASE WHEN s.state = 'completed'
            THEN coalesce((SELECT sum(h.points) FROM odt_agile.sprint_issue h WHERE h.sprint_id = s.id AND h.outcome = 'carried'), 0) END,
       s.completed_at
FROM odt_agile.sprint s
WHERE s.state IN ('active', 'completed');

-- The last 30 days of each project, from the days of issue_state: an issue counts as todo from its creation, in
-- progress from started_on, done from resolved_on (a reopened issue counts by its last state).
INSERT INTO odt_reporting.daily_snapshot (project_id, sprint_id, day, todo, in_progress, done, remaining_points)
SELECT s.project_id, NULL, d.day,
       count(*) FILTER (WHERE s.created_on <= d.day AND (s.started_on IS NULL OR s.started_on > d.day)
                              AND (s.resolved_on IS NULL OR s.resolved_on > d.day)),
       count(*) FILTER (WHERE s.started_on <= d.day AND (s.resolved_on IS NULL OR s.resolved_on > d.day)),
       count(*) FILTER (WHERE s.resolved_on <= d.day),
       coalesce(sum(s.points) FILTER (WHERE s.created_on <= d.day AND (s.resolved_on IS NULL OR s.resolved_on > d.day)), 0)
FROM odt_reporting.issue_state s
CROSS JOIN (SELECT g::date AS day
            FROM generate_series((now() AT TIME ZONE '${reporting_zone}')::date - 29,
                                 (now() AT TIME ZONE '${reporting_zone}')::date, interval '1 day') g) d
GROUP BY s.project_id, d.day;

-- The days of the active sprints so far, with the issues they hold now.
INSERT INTO odt_reporting.daily_snapshot (project_id, sprint_id, day, todo, in_progress, done, remaining_points)
SELECT r.project_id, r.sprint_id, d.day,
       count(s.issue_id) FILTER (WHERE (s.started_on IS NULL OR s.started_on > d.day) AND (s.resolved_on IS NULL OR s.resolved_on > d.day)),
       count(s.issue_id) FILTER (WHERE s.started_on <= d.day AND (s.resolved_on IS NULL OR s.resolved_on > d.day)),
       count(s.issue_id) FILTER (WHERE s.resolved_on <= d.day),
       coalesce(sum(s.points) FILTER (WHERE s.resolved_on IS NULL OR s.resolved_on > d.day), 0)
FROM odt_reporting.sprint_result r
CROSS JOIN LATERAL (SELECT g::date AS day
                    FROM generate_series(r.starts_on, least(r.ends_on, (now() AT TIME ZONE '${reporting_zone}')::date),
                                         interval '1 day') g) d
LEFT JOIN odt_reporting.issue_state s ON s.sprint_id = r.sprint_id
WHERE r.completed_at IS NULL
GROUP BY r.project_id, r.sprint_id, d.day;
