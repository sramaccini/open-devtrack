-- The link «clones» of an issue copied from another, as in Jira: the copy clones the original, which is cloned by it.
ALTER TABLE odt_issue.issue_link DROP CONSTRAINT issue_link_link_type_check;
ALTER TABLE odt_issue.issue_link ADD CONSTRAINT issue_link_link_type_check
    CHECK (link_type IN ('BLOCKS', 'RELATES', 'DUPLICATES', 'CLONES'));
