-- Event Publication Registry of Spring Modulith (spring-modulith-events-jdbc 2.1, schema v2 for PostgreSQL): the events
-- of the modules waiting for their listeners, which run after the commit (plan, architecture).

CREATE TABLE odt.event_publication (
    id                     uuid                     NOT NULL PRIMARY KEY,
    listener_id            text                     NOT NULL,
    event_type             text                     NOT NULL,
    serialized_event       text                     NOT NULL,
    publication_date       timestamp with time zone NOT NULL,
    completion_date        timestamp with time zone,
    status                 text,
    completion_attempts    int,
    last_resubmission_date timestamp with time zone
);

CREATE INDEX event_publication_serialized_event_hash_idx ON odt.event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON odt.event_publication (completion_date);
