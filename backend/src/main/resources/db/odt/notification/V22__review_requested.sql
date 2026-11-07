-- The kind review_requested (ADR-19): a message to whoever is asked to review an issue, with its preference.
ALTER TABLE odt_notification.message DROP CONSTRAINT message_kind_check;
ALTER TABLE odt_notification.message ADD CONSTRAINT message_kind_check
    CHECK (kind IN ('mentioned', 'assigned', 'review_requested', 'commented', 'transitioned'));
ALTER TABLE odt_notification.preference DROP CONSTRAINT preference_kind_check;
ALTER TABLE odt_notification.preference ADD CONSTRAINT preference_kind_check
    CHECK (kind IN ('mentioned', 'assigned', 'review_requested', 'commented', 'transitioned'));
