-- The day the work on an issue should start, as Jira's Start date: optional, never after the due date (checked by the
-- application).
ALTER TABLE odt_issue.issue ADD COLUMN start_on date;
