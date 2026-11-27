-- The project under which the bytes of an attachment are stored (<stored_in>/<id>): it stays when the issue moves to
-- another project (ADR-27), while project_id follows the issue for the quota.
ALTER TABLE odt_collaboration.attachment ADD COLUMN stored_in uuid;
UPDATE odt_collaboration.attachment SET stored_in = project_id;
ALTER TABLE odt_collaboration.attachment ALTER COLUMN stored_in SET NOT NULL;
