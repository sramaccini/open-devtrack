-- The kind moved: an issue you watch moved to another project (ADR-27), and its preference.
ALTER TABLE odt_notification.message DROP CONSTRAINT message_kind_check;
ALTER TABLE odt_notification.message ADD CONSTRAINT message_kind_check
    CHECK (kind IN ('mentioned', 'shared', 'assigned', 'review_requested', 'commented', 'transitioned', 'moved',
                    'webhook_off'));
ALTER TABLE odt_notification.preference DROP CONSTRAINT preference_kind_check;
ALTER TABLE odt_notification.preference ADD CONSTRAINT preference_kind_check
    CHECK (kind IN ('mentioned', 'shared', 'assigned', 'review_requested', 'commented', 'transitioned', 'moved'));
