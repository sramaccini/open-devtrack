-- The secrets of the webhooks are kept encrypted (ADR-23): enc:<key id>:<hex of nonce, ciphertext and tag>, longer
-- than the secret in clear. The secrets stored before are encrypted by the back end at its next start, which needs the
-- key of the installation (ODT_SECRET_KEY): SQL cannot do it.
ALTER TABLE odt_integration.webhook ALTER COLUMN secret TYPE text, ALTER COLUMN previous_secret TYPE text;
