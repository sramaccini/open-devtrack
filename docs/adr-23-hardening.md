# ADR-23: Hardening for 1.0: ASVS level 2 and WCAG 2.2 AA

Status: Accepted (12 November 2026; decided by the maintainer) — F5

## Context

The project plan asks 1.0 to meet OWASP ASVS level 2 and WCAG 2.2 AA. For security it names dependency analysis,
static analysis of the code and a baseline ZAP scan.

What there is today:

- **CI** (`ci.yml`) validates `ui/`, runs the back end tests (Testcontainers) and the end-to-end check with the
  Quadrava kits. It does not look at dependencies, the code or the running application for vulnerabilities.
- **Security that comes from the dependencies:**
  - from authz-library: login with PKCE, sessions on Redis with absolute timeout, CSRF, the login rate limit, Problems
    without details, the bearer access with its rate limit;
  - from the Quadrava runtime: no token in the browser, its own headers.
- **Security that is OpenDevTrack's:** tenant isolation on every query (ADR-09), project roles, attachment names and
  types made safe (ADR-14), the SSRF checks of the webhooks (ADR-21).
- **Known gaps:**
  - the secrets of the webhooks are stored in clear;
  - nothing checks the security headers of the whole application;
  - no list says which ASVS requirement is met where;
  - row-level security of PostgreSQL was left for F5 as a second line (ADR-09).
- **Accessibility:** nothing is checked. Most of it depends on the components of Quadrava (NG-ZORRO); what
  OpenDevTrack controls is its screens: labels, headings, the order of fields, texts, colours of the theme
  (`brand/`).

## Decision

### Security (ASVS 4.0.3, level 2)

1. **`docs/security/asvs.md`**: every level 2 requirement with its state.
   - States: met (where: OpenDevTrack, authz-library, Quadrava, Keycloak, the installation), not applicable (why),
     or open (an issue with the work).
   - It is reviewed at each release; a requirement met by a dependency names its version.
2. **In CI**, three light jobs, each with its own allow-list of accepted findings with a reason:
   - **Dependencies**: OSV-Scanner on `backend/pom.xml` and the lock files of the tools. A known vulnerability of
     high or critical severity fails the build. Dependabot opens the updates.
   - **Code**: CodeQL for Java (free for public repositories), its default security queries. A new alert fails the
     pull request.
   - **Running application**: ZAP baseline scan against the e2e environment, signed in as a user. A new medium or
     high alert fails the build.
3. **Fixes decided now:**
   - The **webhook secrets** are encrypted at rest with AES-GCM under a key of the installation
     (`ODT_SECRET_KEY`), with Spring Security Crypto. A key id is kept with each secret, so the key can be rotated.
     The installation refuses to start in production without the key; development generates one in `.work/.env`.
   - The **security headers** of the API answers (`Content-Security-Policy: default-src 'none'`, `nosniff`,
     `Referrer-Policy`, HSTS behind TLS) are checked by a test. Those of the pages belong to the Quadrava runtime:
     what is missing there is asked (Q).
   - **Row-level security** of PostgreSQL on the tables with a `tenant_id`: a policy on the tenant of the
     transaction (`SET LOCAL odt.tenant`), as a second line under the checks of ADR-09. A test reads across tenants
     with the check off and finds nothing. It goes in only if the load test of ADR-16 stays under its limits (p95 <
     0.6 s); otherwise it is left for 1.x with the numbers.
4. Each open item of the list becomes an issue; 1.0 ships with none at high severity.

### Accessibility (WCAG 2.2 AA)

5. **Automated check**: axe-core run by a headless browser in the e2e job on the main screens:
   - the dashboard, the project list and detail, the issue list, detail and new issue;
   - the board, the backlog, the search, the notification settings, the webhooks.

   A serious or critical violation fails the build, unless it is in the allow-list with the Quadrava request that
   fixes it.
6. **Manual check** at each release, written in `docs/accessibility.md`:
   - keyboard only (every action reachable, visible focus, no trap: the board and the backlog by keyboard too);
   - a screen reader on the creation and editing of an issue;
   - zoom at 200% and a width of 320 px;
   - the contrast of both themes.
7. Problems in a component of Quadrava are asked there (Q), with the screen that shows them; OpenDevTrack does not
   work around them in its screens. Problems in `ui/` are fixed there. Problems in the brand colours are fixed in
   `brand/` first.

## Alternatives considered

- **OWASP Dependency-Check** instead of OSV-Scanner. Heavier: it downloads the NVD, which is slow and needs an API key
  in CI to be usable. OSV-Scanner reads a public database with no key.
- **SonarQube or Semgrep** instead of CodeQL. SonarQube needs a server or a paid cloud plan. Semgrep's free rules
  for Java are fewer. CodeQL runs in GitHub Actions at no cost for a public repository.
- **A full ZAP scan** (active). It needs minutes per screen and a database to throw away. The baseline scan is
  passive and fits in the e2e job; an active scan can be run before a release by hand.
- **Webhook secrets hashed.** They sign, so they must be read back: hashing is not possible.
- **Row-level security as the only barrier.** Already refused in ADR-09.

## Consequences

- `ci.yml`: the jobs for dependencies, CodeQL and ZAP; axe-core in the e2e job; `.github/dependabot.yml`.
- `docs/security/asvs.md`, `docs/accessibility.md`, their allow-lists, the update of `SECURITY.md` (supported
  versions from 1.0).
- `integration`: encryption of the secrets, with a migration that encrypts the existing ones. `ODT_SECRET_KEY` in the
  stack and the e2e (generated), in `backend/README.md` and in the deployment notes.
- `platform`: the header test. Migrations for row-level security, and the transaction tenant set by `platform`.
- Requests to Quadrava for the headers of the runtime image and for what axe finds in its components. Nothing for
  authz-library is known yet: what the ASVS list finds there is asked there.
