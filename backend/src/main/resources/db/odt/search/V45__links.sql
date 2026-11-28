-- The links of an issue (ADR-29), so that ODQL linked, linkType and linkedIssues() find it. Three words per link:
-- t:<type>, i:<the other issue> and r:<type>:<o|i>:<the other issue>, o when the issue is the from end. The issues
-- are named by their identifiers, which a move does not change. Written by the listeners as the other fields; the
-- links already there are written here.
ALTER TABLE odt_search.issue_doc ADD COLUMN links text[] NOT NULL DEFAULT '{}';

UPDATE odt_search.issue_doc d
SET links = words.links
FROM (
    SELECT issue_id, array_agg(DISTINCT word) AS links
    FROM (
        SELECT l.from_id AS issue_id, w AS word
        FROM odt_issue.issue_link l,
             unnest(ARRAY['t:' || l.link_type, 'i:' || l.to_id, 'r:' || l.link_type || ':o:' || l.to_id]) w
        UNION ALL
        SELECT l.to_id, w
        FROM odt_issue.issue_link l,
             unnest(ARRAY['t:' || l.link_type, 'i:' || l.from_id, 'r:' || l.link_type || ':i:' || l.from_id]) w
    ) ends
    GROUP BY issue_id
) words
WHERE d.issue_id = words.issue_id;

CREATE INDEX ix_doc_links ON odt_search.issue_doc USING gin (links);
