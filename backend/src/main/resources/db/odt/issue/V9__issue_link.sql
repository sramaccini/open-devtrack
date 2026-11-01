-- Links between issues (module issue): ODT-1 blocks ODT-2, relates to, duplicates. One row per link, read from both
-- ends; both issues are of the same tenant (ADR-09), possibly of two projects. Inside the schema of the module, so the
-- links of a deleted issue go with it.

CREATE TABLE odt_issue.issue_link (
    id         uuid         PRIMARY KEY DEFAULT uuidv7(),
    from_id    uuid         NOT NULL REFERENCES odt_issue.issue (id) ON DELETE CASCADE,
    to_id      uuid         NOT NULL REFERENCES odt_issue.issue (id) ON DELETE CASCADE,
    link_type  varchar(20)  NOT NULL CHECK (link_type IN ('BLOCKS', 'RELATES', 'DUPLICATES')),
    created_by varchar(150) NOT NULL,
    created_at timestamptz  NOT NULL,
    CHECK (from_id <> to_id),
    UNIQUE (from_id, to_id, link_type)
);

CREATE INDEX ix_issue_link_to ON odt_issue.issue_link (to_id);
