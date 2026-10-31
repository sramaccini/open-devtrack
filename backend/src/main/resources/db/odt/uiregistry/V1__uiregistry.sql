-- Versions and publications of the screen definitions (module uiregistry, ADR-03).

CREATE TABLE odt_uiregistry.document_version (
    id          uuid         PRIMARY KEY DEFAULT uuidv7(),
    app_id      varchar(100) NOT NULL,
    kind        varchar(20)  NOT NULL,
    document_id varchar(100) NOT NULL,
    hash        varchar(64)  NOT NULL,
    -- The text as saved: its SHA-256 is the identifier of the version
    content     text         NOT NULL,
    author      varchar(255) NOT NULL,
    state       varchar(20)  NOT NULL,
    reviewed_by varchar(255),
    reviewed_at timestamptz,
    review_note varchar(500),
    created_at  timestamptz  NOT NULL,
    version     bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_document_version_hash UNIQUE (app_id, kind, document_id, hash)
);

CREATE INDEX ix_document_version_latest ON odt_uiregistry.document_version (app_id, kind, document_id, created_at DESC);

CREATE TABLE odt_uiregistry.publication (
    id           uuid         PRIMARY KEY DEFAULT uuidv7(),
    app_id       varchar(100) NOT NULL,
    kind         varchar(20)  NOT NULL,
    document_id  varchar(100) NOT NULL,
    environment  varchar(100) NOT NULL,
    hash         varchar(64)  NOT NULL,
    published_at timestamptz  NOT NULL,
    CONSTRAINT uq_publication_environment UNIQUE (app_id, kind, document_id, environment)
);
