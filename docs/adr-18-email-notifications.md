# ADR-18: Email, preferences and digests

Status: Accepted (7 November 2026; decided by the maintainer) — F4, epic E9

## Context

E9 of the project plan asks for in-app and email notifications, preferences per user and digests; the plan's stack
names Spring Mail with Thymeleaf templates. ADR-13 built the in-app part: messages in `odt_notification.message` for
four kinds (`mentioned`, `assigned`, `commented`, `transitioned`), each kept as a kind with its values and written in
the reader's language when read, behind the bell of Quadrava; it left email, digests and preferences to a later step
of the same module.

The email address of a user comes from `identity` (`TenantUser`). The language of a message follows `Accept-Language`
of the request that reads it; a mail is written by a job, with no request.

## Decision

1. **Preferences per user and kind**, `odt_notification.preference (tenant_id, username, kind, in_app, email,
   language)`, with `email` one of `off`, `instant` or `daily`. Without a row the defaults apply: in-app on for every
   kind; email `instant` for `mentioned` and `assigned`, `daily` for `commented` and `transitioned`. `language`
   (`en` or `it`) is taken from `Accept-Language` when the user saves the preferences or reads the messages, so mails
   are written in the language the user last used. Turning in-app off for a kind stops creating those messages for
   the user.
2. **An outbox, not a send in the listener.** A message to be mailed gets `email_due_at` (now for `instant`, the next
   digest time for `daily`); a scheduled job (every minute) sends what is due and sets `emailed_at`. A failure is
   retried with a growing delay, five times, then given up and logged; nothing is sent twice for a message, and a
   restart loses nothing. The 90 days of ADR-13 apply.
3. **Instant mails and digests.** An instant mail tells one message, with a link to the issue. A digest, once a day at
   `odt.mail.digest-at` (default 07:00 of `odt.reporting.zone`, ADR-17), groups the user's due messages by issue in
   one mail; none when there is nothing. A message read in the application before its digest is left out of it.
4. **Plain text mails** from the catalogues of the back end that already write the messages (English and Italian),
   with the subject `[OpenDevTrack] WEB-42 Checkout fails with two coupons` and links built from
   `odt.public-url`. No HTML and no template engine: the plan's Thymeleaf is not needed for text, and text cannot carry
   markup from a summary or a comment. HTML can come later if users ask for it.
5. **Spring Mail**, configured with the usual `spring.mail.*`; without `spring.mail.host` the email channel is off:
   preferences are kept, nothing is sent, and the screen says that email is not configured. The development and e2e
   environments run Mailpit, so a mail can be looked at.
6. **The screen.** «Notification settings» belongs in the user menu and the settings dialog of Quadrava, which an
   application cannot extend yet: requested as Q13 (7 November 2026). Until then a screen in the navigation: one row per kind, with in-app on or off and email off, instant or daily, and the address the mails
   go to (read only, from the identity provider). `GET/PUT /api/notifications/preferences`.

## Alternatives considered

- **Send from the listener of the event.** Simpler, but a slow or down mail server slows or fails the processing of
  the event, and digests need a store of what is due anyway.
- **Thymeleaf HTML templates**, as the plan said: richer mails, a dependency and templates to keep in two languages,
  and markup to escape from user text. Text first.
- **Preferences per project.** Finer, but most users want one rule; a project rule can be added to the same table
  later.
- **A mail service (SES, SendGrid) through its API.** Any of them speaks SMTP too: SMTP keeps the instance free of a
  provider.

## Consequences

- Module `notification`: migration (preferences; `email_due_at`, `emailed_at` and attempts on the message), the job,
  the texts of the mails in the catalogues, the dependency `spring-boot-starter-mail`.
- Configuration `odt.public-url`, `odt.mail.digest-at`, `odt.mail.from`; Mailpit in `e2e/authz/compose.yaml` and a
  check that a mention sends a mail.
- Tests: defaults and preferences, instant and digest (with a clock), retry and give-up, no mail when the host is not
  set, the language of the user.
- `ui/`: the settings screen and its data source; the note in the README about configuring mail.
- The known limit of ADR-13 («the text of the messages follows the browser, not the language of the settings»)
  remains for the bell; mails use the stored `language`.
