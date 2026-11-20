# ADR-25: Documentation, public demo and the 1.0 release

Status: Accepted (19 November 2026; decided by the maintainer) — F5

## Context

F5 is built: the public API and webhooks (ADR-21), the administration (ADR-22) and the hardening (ADR-23, ADR-24,
with no open item left on GitHub). HANDOFF.md ends F5 with «the documentation, the public demo and 1.0», planned for
28 May 2027. Each of the three needs a choice before it is built.

What there is today:

- **Documentation for those who work on the code**: README.md, AGENTS.md, the ADRs, `backend/README.md` (one section
  per module), `ui/README.md`, `docs/api.md` with `docs/openapi.json`, `docs/security/`, `docs/operations/`.
- **Nothing for the users of the tracker**: what ODQL can do is only in ADR-16 and `backend/README.md`. The workflows,
  boards, sprints, dashboards, notifications and «Your data» are described only for developers, in the same places.
- **No way to install it**. `stack/` is development only (plain HTTP, the profile `dev`, Keycloak on its embedded
  database) and says so. The tag workflow (`release.yml`) publishes only the back end image. The screens of `ui/` reach
  the registry with `quadrava publish` and an author's token. The realm exists only as the one of the e2e
  (`e2e/authz/realm-opendevtrack.json`), which has the development users. ADR-01 speaks of «the Compose and Helm
  files», and neither exists.
- **Building needs a GitHub token even to read.** authz-library is on GitHub Packages (Maven), and so is the Quadrava
  CLI (npm). An installation from the published images needs no token, but a contributor who builds the back end does.
- **No release yet.** `release.yml` matches only `vX.Y.Z`, so a release candidate (`v1.0.0-rc.1`) would publish
  nothing, and the manual accessibility check is to run «on the first release candidate» (`docs/accessibility.md`).
  The Dependabot secret `AUTHZ_PACKAGES_TOKEN` is not set (`gh secret list --app dependabot` is empty), so the weekly
  Maven updates fail. There is no CHANGELOG.

## Decision

1. **Documentation in Markdown in the repository, no site generator for 1.0.**
   - `docs/guide/`: the user guide, one page per area. The areas are:
     - projects and members;
     - issues, custom fields, links and watchers;
     - workflows;
     - boards, backlog and sprints;
     - search and ODQL, with the full reference of the language;
     - dashboards and reports;
     - notifications and preferences;
     - the public API (a link to `docs/api.md`);
     - «Your data» and «Your sessions».
   - `docs/admin/`: the administrator guide. It covers installation (below) and configuration: every variable with its
     default, read from `application.yml`. It also covers:
     - the Keycloak realm and theme;
     - mail, attachments (the stores, quota, clamd);
     - the secret key and Vault transit;
     - backups of PostgreSQL and of the attachment store;
     - upgrades: Flyway at start-up, the order of back end, `ui/` and runtime;
     - the administration screens, permission schemes and groups.

     It links to `docs/operations/` and to «The installation» of asvs.md instead of repeating them.
   - English only. The screens have their English and Italian catalogues, and the guide uses their English labels.
   - Screenshots are taken from `stack/` with its sample projects. They are PNG files in `docs/guide/images/`, light
     theme, one for each screen that needs one.
   - GitHub renders the pages. A site (GitHub Pages with the `brand/` identity) can come in 1.x from the same files,
     without rewriting them.
   - **Note of 19 November 2026, step 3.** The user guide is built as written. Its images are taken by a script,
     `e2e/a11y/screenshots.mjs` (`stack/stack.sh screenshots`), with the Playwright that the accessibility check
     already pins, so they can be taken again when the screens change. They are best taken on a stack started empty,
     so that the dates of the samples are recent. Writing the ODQL reference found a defect: `=` and `IN` do not match
     a choice of a custom field with several choices (#30), fixed in the next commit.
2. **One way to install for 1.0: a production Compose file; Helm only when asked.**
   - `deploy/compose/` contains:
     - a `compose.yaml` with the back end image of the release (by digest), the Quadrava runtime of the pinned version,
       PostgreSQL and Redis. Keycloak, TLS and the reverse proxy are the installation's, with a commented example;
     - a `.env.example` with every variable and no value that works as is;
     - a realm template without users (`deploy/keycloak/realm-opendevtrack.json`): clients, roles, the theme, the
       password policy, brute force detection, OTP;
     - a script that publishes the `ui/` of the tag with the Quadrava CLI of the pinned version.
   - `release.yml` attaches `ui/`, the realm template and `deploy/compose/` to the GitHub release as one archive. An
     installation then needs no checkout and no build.
   - The CI starts that `compose.yaml` and runs the `check` of `e2e/authz` against it. It does this on each tag, and on
     `main` when `deploy/` changes.
   - A Helm chart is a 1.x item, built when an installation asks for it. ADR-01's «Compose and Helm» becomes «Compose
     for 1.0».
   - **Note of 19 November 2026, step 1.** Three changes, made while building it:
     - The commented examples of Keycloak and of the proxy are services of the Compose file, in the optional profiles
       `keycloak` and `proxy` (Caddy). An installation that has its own leaves the profile out. The demo uses both,
       and the CI checks both.
     - The `check` of `e2e/authz` needs the development users, which an installation does not have. So the CI runs a
       check of its own, `e2e/deploy/run.sh`. It does what the guide says (a user in Keycloak, `first-admin`,
       `publish-ui`), then signs in over https and checks the headers, the cookie, the permissions and a project.
     - The realm template does not enforce OTP by itself: the administrators are given *Configure OTP*
       (`docs/admin/keycloak.md`).
3. **A public demo, reset every night, with shared accounts and nothing that leaves it.** The maintainer chooses the
   host and the domain (open question 1). It runs `deploy/compose/` itself, so it also tests the install path, with a
   few demo settings:
   - three demo users with the roles of a member, a project admin and a tenant admin. Their passwords are in the
     README of the demo, so they are public. There is no self-registration, so the demo collects no email and no
     personal data.
   - Users cannot change their password or OTP in the Keycloak account console: no required actions, and the account
     console is off for the demo clients. That way one visitor cannot lock out the others.
   - Every night the database, Redis and the attachments are restored from the image of the sample projects of
     `stack/`.
   - Mail goes to a Mailpit of the demo whose web page is not published. Webhooks already go only to public addresses over https (`integration/Addresses`). In the
     demo they go only to the `echo` of the demo, at its public name, so that visitors cannot use the demo to send
     requests to other hosts. That needs a new setting, `ODT_WEBHOOKS_ALLOWED_HOSTS`, built with this ADR. Attachments have a quota of
     10 MB per project.
   - The global administrator, the registry author and Vault are not used in the demo. The secret key comes from the
     host's environment.
   - It uses TLS, the default profile (never `dev`), the ZAP baseline before the first opening, and the same alerts as
     `docs/operations`. The page of the demo states that everything is erased every night and that nothing private
     should be entered.
   - **Note of 20 November 2026, step 4.** `ODT_WEBHOOKS_ALLOWED_HOSTS` is built, and so is everything of the
     demo that does not need its host, in `deploy/demo/`:
     - a Compose file on top of `deploy/compose`, with Mailpit, the echo behind Caddy (`POST` only) and the demo
       settings;
     - `demo.sh` with `setup`, the nightly `reset` and `upgrade`;
     - the page of the demo.

     Three details:
     - The sample projects are not restored from an image. `reset` puts back a base snapshot with only the users, the
       roles and the published screens, then adds the samples again through the API. That way their dates stay
       relative to the day. The samples moved from `stack/stack.sh` to `stack/samples.sh`, which both use.
     - The three users are `mario.rossi` (admin of the sample projects), `anna.bianchi` (member) and `giulia.verdi`
       (admin of the tenant).
     - Failed logins do not lock an account in the demo, since the passwords are public.

     The deployment, the ZAP scan and the alerts wait for the host (#28).
4. **1.0 is released through release candidates, with a written checklist.**
   - `release.yml` also accepts `vX.Y.Z-rc.N`. A candidate publishes its image, signature and SBOM like a release,
     with the same tags, but as a pre-release: it never moves `X.Y` or `latest`.
   - `docs/release.md` holds the checklist of each release:
     - CI green on the tag, and no open issue labelled for that version or of high severity (ADR-23);
     - the manual accessibility check of `docs/accessibility.md` on the candidate;
     - the ZAP baseline against `deploy/compose/`;
     - `cosign verify` as `docs/operations` says;
     - the upgrade from the previous release tested on a copy of the demo data;
     - `CHANGELOG.md` updated, in the Keep a Changelog format, written by hand from the commits;
     - the version pins of Quadrava and authz-library being releases, never snapshots.
   - What 1.0 promises, written in `docs/release.md`:
     - the API compatibility of `docs/api.md` within 1.x;
     - the support of SECURITY.md;
     - Flyway migrations never edited after a release, only added;
     - an upgrade path from every 1.x to the next.
   - The maintainer sets `AUTHZ_PACKAGES_TOKEN` for Dependabot before the first candidate (open question 3).
   - **Note of 19 November 2026, step 2.** Built as written, with three details:
     - `release.yml` calls `deploy.yml` before it publishes anything. A path filter does not decide on a tag, so
       `deploy.yml` itself runs only on `main`.
     - The archive also carries the licence, NOTICE, TRADEMARKS and the changelog, and its `.env.example` names the
       image of the release by digest.
     - The ZAP baseline of the checklist is the signed-in one of `e2e/authz` on the tag. The scan against
       `deploy/compose` is the demo's, before it opens.
5. **Order of the work, one commit each.**
   1. the administrator guide with `deploy/compose/` and its CI;
   2. the release candidates in `release.yml`, `docs/release.md` and `CHANGELOG.md`;
   3. the user guide with its screenshots;
   4. the setting `ODT_WEBHOOKS_ALLOWED_HOSTS` and the demo itself, once its host exists;
   5. `v1.0.0-rc.1`, its checks, then `v1.0.0`.

   README.md is brought up to date in step 1: its status still says «F5 under way» and «administration and hardening
   accepted».

## Open questions for the maintainer

1. **The host and domain of the demo**: a small VPS (2 vCPU, 4 GB) holds the Compose stack and a Keycloak, at about
   €5–10 a month. A managed platform costs more and adds nothing the demo needs. The cost and the account are the
   maintainer's.
2. **authz-library and the Quadrava CLI on public registries** (Maven Central, npmjs.com), so that building needs no
   token. This would be a request to each project (A11, Q25). It is not required for 1.0, since installations use the
   images.
3. **`AUTHZ_PACKAGES_TOKEN`**: a token with `read:packages`, set in Settings → Secrets → Dependabot.

## Alternatives considered

- **A documentation site now** (MkDocs Material, Docusaurus): it adds a build, a dependency and a deployment for text
  that GitHub already renders. It can be added later from the same Markdown.
- **A Helm chart for 1.0**: twice the install paths to test, and nobody has asked for Kubernetes yet. The Compose file
  is also what the demo runs.
- **No public demo, only `stack/`**: trying the tracker would then need Docker, a GitHub token and a build. A demo
  can be opened with one link.
- **A demo with self-registration**: each visitor would have their own data, but the demo would collect emails, which
  would need a privacy notice, the export and removal path used for real, and protection against spam accounts. That
  is too much for a demo.
- **Releasing 1.0 straight from a tag, without candidates**: the manual accessibility check and the upgrade test
  need a published build to run on, and a candidate is that build.

## Consequences

- New directories `deploy/` and `docs/guide/`, `docs/admin/`, and the files `docs/release.md` and `CHANGELOG.md`. CI
  gains one job that starts `deploy/compose/`.
- One new setting in the back end, `ODT_WEBHOOKS_ALLOWED_HOSTS`. It is empty by default, which means every public
  host, as today. It is also useful outside the demo, as a narrower limit chosen by the installation, on top of the
  public-address rule.
- The demo is a running service with a cost and a maintainer. If it is down, the README says so instead of linking
  to it.
- The documentation becomes part of the review: a change of behaviour updates the guide in the same commit (AGENTS.md
  §4), as it already does for the developer READMEs.
