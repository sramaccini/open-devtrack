-- Search (module search, ADR-16): a read model of the issues for ODQL, written after the commit from the events of the
-- modules issue and agile, and the saved filters. Projects, issues and sprints are only identifiers: no foreign key to
-- another module's schema.

CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA odt_search;
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA odt_search;

-- unaccent is STABLE (it reads a dictionary); fixed to the dictionary of this schema it can be IMMUTABLE, as an index
-- expression or a stored text vector needs.
CREATE FUNCTION odt_search.fold(value text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    AS $$ SELECT lower(odt_search.unaccent('odt_search.unaccent'::regdictionary, value)) $$;

-- The text of an issue as a vector: summary, description and labels, configuration simple (issues mix Italian and
-- English), without accents. The description is in the vector only, not stored.
CREATE FUNCTION odt_search.text_vector(summary text, description text, labels text[]) RETURNS tsvector
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    AS $$ SELECT to_tsvector('simple', odt_search.fold(coalesce(summary, '') || ' ' || coalesce(description, '') || ' '
                                                        || coalesce(array_to_string(labels, ' '), ''))) $$;

-- One row per issue. Values in the form of the API: type and priority in lower case, priority_order from 1 (lowest)
-- to 5 (highest) for < and >; category of the status from the workflow; resolved_at when the issue entered a done
-- status.
CREATE TABLE odt_search.issue_doc (
    issue_id       uuid         PRIMARY KEY,
    tenant_id      varchar(50)  NOT NULL,
    project_id     uuid         NOT NULL,
    project_key    varchar(10)  NOT NULL,
    issue_key      varchar(40)  NOT NULL,
    number         bigint       NOT NULL,
    type           varchar(20)  NOT NULL,
    status         varchar(50)  NOT NULL,
    category       varchar(20)  NOT NULL,
    priority       varchar(20)  NOT NULL,
    priority_order smallint     NOT NULL,
    assignee       varchar(150),
    reporter       varchar(150) NOT NULL,
    labels         text[]       NOT NULL DEFAULT '{}',
    summary        varchar(255) NOT NULL,
    custom         jsonb        NOT NULL DEFAULT '{}',
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    resolved_at    timestamptz,
    sprint_id      uuid,
    points         int,
    rank           varchar(200) COLLATE "C",
    tsv            tsvector     NOT NULL
);

CREATE INDEX ix_doc_project_updated ON odt_search.issue_doc (project_id, updated_at DESC);
CREATE INDEX ix_doc_project_status ON odt_search.issue_doc (project_id, status);
CREATE INDEX ix_doc_project_category ON odt_search.issue_doc (project_id, category);
CREATE INDEX ix_doc_assignee ON odt_search.issue_doc (assignee, project_id);
CREATE INDEX ix_doc_reporter ON odt_search.issue_doc (reporter, project_id);
CREATE INDEX ix_doc_created ON odt_search.issue_doc (project_id, created_at);
CREATE INDEX ix_doc_resolved ON odt_search.issue_doc (project_id, resolved_at);
CREATE INDEX ix_doc_sprint ON odt_search.issue_doc (sprint_id);
CREATE INDEX ix_doc_rank ON odt_search.issue_doc (project_id, rank);
CREATE INDEX ix_doc_key ON odt_search.issue_doc (issue_key);
CREATE INDEX ix_doc_labels ON odt_search.issue_doc USING gin (labels);
CREATE INDEX ix_doc_custom ON odt_search.issue_doc USING gin (custom jsonb_path_ops);
CREATE INDEX ix_doc_tsv ON odt_search.issue_doc USING gin (tsv);
CREATE INDEX ix_doc_summary ON odt_search.issue_doc USING gin (odt_search.fold(summary) odt_search.gin_trgm_ops);

-- Saved filters: private to their owner, or shared with every user of the tenant (what a filter finds still depends on
-- what each user may browse). on_dashboard: shown on the personal dashboard (ADR-17).
CREATE TABLE odt_search.filter (
    id           uuid          PRIMARY KEY DEFAULT uuidv7(),
    tenant_id    varchar(50)   NOT NULL,
    owner        varchar(150)  NOT NULL,
    name         varchar(100)  NOT NULL,
    query        varchar(2000) NOT NULL,
    shared       boolean       NOT NULL DEFAULT false,
    on_dashboard boolean       NOT NULL DEFAULT false,
    created_at   timestamptz   NOT NULL,
    updated_at   timestamptz   NOT NULL,
    version      bigint        NOT NULL DEFAULT 0
);

CREATE INDEX ix_filter_owner ON odt_search.filter (tenant_id, owner);
CREATE INDEX ix_filter_shared ON odt_search.filter (tenant_id) WHERE shared;

-- The issues there are already, with their plan and the category of their status.
INSERT INTO odt_search.issue_doc (issue_id, tenant_id, project_id, project_key, issue_key, number, type, status, category,
                                  priority, priority_order, assignee, reporter, labels, summary, custom, created_at,
                                  updated_at, resolved_at, sprint_id, points, rank, tsv)
SELECT i.id, i.tenant_id, i.project_id, p.project_key, p.project_key || '-' || i.number, i.number, lower(i.type),
       i.status, coalesce(s.category, 'todo'), lower(i.priority),
       array_position(ARRAY['LOWEST', 'LOW', 'MEDIUM', 'HIGH', 'HIGHEST'], i.priority::text), i.assignee, i.reporter,
       i.labels, i.summary, i.custom, i.created_at, i.updated_at,
       CASE WHEN s.category = 'done' THEN i.updated_at END, pl.sprint_id, pl.points, pl.rank,
       odt_search.text_vector(i.summary, i.description, i.labels)
FROM odt_issue.issue i
JOIN odt_project.project p ON p.id = i.project_id
LEFT JOIN odt_workflow.workflow w ON w.project_id = i.project_id
LEFT JOIN odt_workflow.status s ON s.workflow_id = w.id AND s.code = i.status
LEFT JOIN odt_agile.issue_plan pl ON pl.issue_id = i.id;
