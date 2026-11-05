-- Mentions in the comments (ADR-13): the members of the project named as @username in the text, kept so an edit tells
-- only the people newly mentioned. They go with their comment.

CREATE TABLE odt_collaboration.mention (
    comment_id uuid         NOT NULL REFERENCES odt_collaboration.comment (id) ON DELETE CASCADE,
    username   varchar(150) NOT NULL,
    PRIMARY KEY (comment_id, username)
);
