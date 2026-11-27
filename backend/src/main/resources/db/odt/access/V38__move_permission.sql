-- odt.issue.move (move an issue to another project, as Move Issues in Jira, ADR-27) joins the catalogue: the role admin
-- of every scheme gets it, as the default sets of ProjectRole do; the other roles get it from an admin of the schemes.
UPDATE odt_access.permission_scheme
SET admin = array_append(admin, 'odt.issue.move')
WHERE NOT ('odt.issue.move' = ANY (admin));
