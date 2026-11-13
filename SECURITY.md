# Security policy

## Reporting a vulnerability

Please do **not** open a public issue for a security problem. Use GitHub's private reporting instead: on the repository page, **Security → Report a vulnerability**. Describe the problem, how to reproduce it and the version (commit) affected.

You will get an answer within a week. Once a fix is available, the advisory is published with credit to the reporter, unless they prefer otherwise.

## Scope

- The back end, the Quadrava project in `ui/` and the deployment files of this repository.
- Login, session and global roles come from [authz-library](https://github.com/sramaccini/authz-library), the screens are drawn by [Quadrava](https://github.com/sramaccini/quadrava): a problem in them is reported in their repositories.
- The development environments (`e2e/`, Docker Compose files) use generated test credentials and must not be exposed. Problems there are welcome, but they are not vulnerabilities of a production component.

## Supported versions

Until 1.0, only the latest commit of `main` is supported. From 1.0, the latest release gets the security fixes.

| Version | Security fixes |
| --- | --- |
| `main` | Yes |
| latest 1.x release | From the 1.0 release |

## How the project checks itself

- The requirements of OWASP ASVS 4.0.3 level 2, each with its state and the open work:
  [docs/security/asvs.md](docs/security/asvs.md). An installation must do its part, listed there under «The
  installation».
- In CI (ADR-23):
  - OSV-Scanner on the dependencies (a high or critical vulnerability fails the build);
  - CodeQL on the Java code;
  - a ZAP baseline scan of the running application, signed in;
  - axe-core on the main screens;
  - Dependabot opens the updates.
- Each accepted finding is in an allow-list with its reason: `osv-scanner.toml`, `e2e/zap/allowlist.json`,
  `e2e/a11y/allowlist.json`, and the dismissed alerts of code scanning.
