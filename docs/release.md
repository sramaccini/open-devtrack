# Releases

How a version of OpenDevTrack is released (ADR-25 point 4), and what a release promises. The maintainer releases.
`.github/workflows/release.yml` publishes; the steps around it are below.

## Versions

- `vX.Y.Z` is a release; `vX.Y.Z-rc.N` is a release candidate of it.
- The tag runs `release.yml`, which does the same for both:
  1. the build and the tests;
  2. the installation of `deploy/compose`, checked with an image built from the tag (`deploy.yml`);
  3. the image `ghcr.io/sramaccini/opendevtrack-backend`, signed with cosign, with its SBOM attested;
  4. a GitHub release with the SBOM and the archive `opendevtrack-<tag>.tar.gz` and its SHA-256. The archive holds
     `deploy/`, `ui/`, `keycloak/`, `brand/`, the licence and this changelog, and its `.env.example` names the image by
     digest.
- What differs:
  - a release tags the image `X.Y.Z`, `X.Y` and `latest`;
  - a candidate tags the image `X.Y.Z-rc.N` only, and its GitHub release is a pre-release.
- Each minor version starts with a candidate. A patch release with only fixes may go without one.

## Checklist

For a candidate, every item is done. For the release itself, the first two are done again, the others carry over
from its last candidate if nothing changed since.

**Before the tag**

- [ ] CI and CodeQL are green on the commit of the tag (the installation is checked by the tag itself).
- [ ] No open issue has the milestone of the version, and none has high severity (ADR-23).
- [ ] The versions of Quadrava and authz-library are releases, never snapshots, in `backend/pom.xml`, the Compose
      files, `ci.yml` and the e2e scripts.
- [ ] `CHANGELOG.md`: the section `[Unreleased]` becomes `[X.Y.Z] - <date>`, written from the commits since the last
      tag (`git log --oneline vA.B.C..HEAD`). Use the headings Added, Changed, Deprecated, Removed, Fixed, Security,
      and add an «Upgrading» paragraph when an installation must do something.
- [ ] The documentation follows the changes: `docs/admin/`, `docs/api.md`, `docs/openapi.json`.

**The tag**

```bash
git tag -a v1.0.0-rc.1 -m "OpenDevTrack 1.0.0-rc.1"
git push origin v1.0.0-rc.1
```

**After the workflow**

- [ ] The image is verified as [operations](operations/README.md) says, «Verifying the image»: the signature and the
      SBOM.
- [ ] The archive installs: unpack it on a clean host, follow [installation.md](admin/installation.md), sign in.
- [ ] An upgrade from the previous release, on a copy of its data: back up, start the new image, `./odt.sh
      publish-ui`, sign in, open an issue, a board and a dashboard ([upgrades-and-backups.md](admin/upgrades-and-backups.md)).
- [ ] The manual accessibility check of [accessibility.md](accessibility.md), with its result in its table.
- [ ] The ZAP baseline signed in (`e2e/authz/run.sh zap` on the tag) with no medium or high alert. The demo runs it
      against `deploy/compose` before it opens (ADR-25 point 3).
- [ ] The release notes link the `CHANGELOG.md` section, and say any step of «Upgrading».

A failed item is fixed on `main` and goes into a new candidate. A tag is never moved or deleted once its workflow has
published.

## What a release promises

From 1.0:

- **The API**: within 1.x, no public path or field is removed or renamed. A deprecated one is marked in the OpenAPI
  definition and kept for the rest of 1.x ([api.md](api.md), «Versions and stability»).
- **The data**: a Flyway migration is never edited after a release that holds it, only added to. A release upgrades
  from the one before it, in order: 1.2 to 1.3 to 1.4.
- **Security**: the latest 1.x release gets the security fixes ([SECURITY.md](../SECURITY.md)).
- **The images**: every published image is signed by `release.yml` for its tag, with its SBOM.
