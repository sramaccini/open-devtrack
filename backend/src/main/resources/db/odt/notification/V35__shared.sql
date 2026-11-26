-- The kind shared: a member shared an issue with you, with an optional message (Jira's «Share»), and its preference.
ALTER TABLE odt_notification.message DROP CONSTRAINT message_kind_check;
ALTER TABLE odt_notification.message ADD CONSTRAINT message_kind_check
    CHECK (kind IN ('mentioned', 'shared', 'assigned', 'review_requested', 'commented', 'transitioned', 'webhook_off'));
ALTER TABLE odt_notification.preference DROP CONSTRAINT preference_kind_check;
ALTER TABLE odt_notification.preference ADD CONSTRAINT preference_kind_check
    CHECK (kind IN ('mentioned', 'shared', 'assigned', 'review_requested', 'commented', 'transitioned'));
