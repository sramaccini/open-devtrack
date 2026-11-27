-- odt.reporter.edit (change the reporter of an issue, as Modify Reporter in Jira) joins the catalogue: the role admin
-- of every scheme gets it, as the default sets of ProjectRole do; the other roles get it from an admin of the schemes.
UPDATE odt_access.permission_scheme
SET admin = array_append(admin, 'odt.reporter.edit')
WHERE NOT ('odt.reporter.edit' = ANY (admin));
