-- The kind webhook_off (ADR-21, B11): a message to the admins of a project whose webhook was turned off. In the
-- application only, with no choice in the settings, so the preferences keep their kinds.
ALTER TABLE odt_notification.message DROP CONSTRAINT message_kind_check;
ALTER TABLE odt_notification.message ADD CONSTRAINT message_kind_check
    CHECK (kind IN ('mentioned', 'assigned', 'review_requested', 'commented', 'transitioned', 'webhook_off'));
