-- odt.worklog.add (log work, and change or delete one's own work logs) and odt.worklog.manage (change or delete those of
-- others) join the catalogue (ADR-30), as Work On Issues and Edit/Delete All Worklogs in Jira: every scheme gives the
-- first to the roles that comment and both to the role admin, as the default sets of ProjectRole do.
UPDATE odt_access.permission_scheme
SET developer = array_append(developer, 'odt.worklog.add')
WHERE 'odt.issue.comment' = ANY (developer) AND NOT ('odt.worklog.add' = ANY (developer));

UPDATE odt_access.permission_scheme
SET viewer = array_append(viewer, 'odt.worklog.add')
WHERE 'odt.issue.comment' = ANY (viewer) AND NOT ('odt.worklog.add' = ANY (viewer));

UPDATE odt_access.permission_scheme
SET admin = array_append(admin, 'odt.worklog.add')
WHERE NOT ('odt.worklog.add' = ANY (admin));

UPDATE odt_access.permission_scheme
SET admin = array_append(admin, 'odt.worklog.manage')
WHERE NOT ('odt.worklog.manage' = ANY (admin));
