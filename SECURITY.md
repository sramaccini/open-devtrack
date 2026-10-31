# Security policy

## Reporting a vulnerability

Please do **not** open a public issue for a security problem. Use GitHub's private reporting instead: on the repository page, **Security → Report a vulnerability**. Describe the problem, how to reproduce it and the version (commit) affected.

You will get an answer within a week. Once a fix is available, the advisory is published with credit to the reporter, unless they prefer otherwise.

## Scope

- The back end, the Quadrava project in `ui/` and the deployment files of this repository.
- Login, session and global roles come from [ms-authz](https://github.com/sramaccini/authz-library), the screens are drawn by [Quadrava](https://github.com/sramaccini/quadrava): a problem in them is reported in their repositories.
- The development environments (`e2e/`, Docker Compose files) use generated test credentials and must not be exposed. Problems there are welcome, but they are not vulnerabilities of a production component.

## Supported versions

Until the first release, only the latest commit of `main` is supported.
