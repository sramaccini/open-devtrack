# ADR-14: Attachments and where their files are kept

Status: Proposed (6 November 2026) — F2 E4

## Context

E4 ends with the attachments of the issues. The plan left open where the files are kept: an installation with one
server and a small team wants nothing more than PostgreSQL, a larger one a disk or volume of its own, a cloud one an
object storage. The maintainer chose three plugins behind one interface: **database** (the default),
**filesystem** and **object storage compatible with S3** (AWS S3, MinIO, Ceph, Garage and the like).

Quadrava gives the upload: a form field of type `file` sends the chosen file at once to a POST data source
(`upload`), as the `file` part of a multipart request, up to 100 MB (`maxSizeKb`). It has no way to download a file
from the row of a table: a column runs steps (`onClick`), and steps navigate only to screens; the `link` component
opens a relative path, but it is not a column.

The runtime image forwards `/api/` to the back end through nginx without `client_max_body_size`, so nginx keeps
its default of 1 MB: a larger upload through the runtime is refused with 413 before it reaches the back end.

## Decision

1. **One interface, `AttachmentStore`**, in the module `collaboration`, which keeps the attachments with the
   comments: `put(key, stream, size)`, `get(key)` as a stream, `delete(key)`. The key is
   `<project id>/<attachment id>`, two UUIDs: the name the user gave the file never reaches a path or an object name.
   The metadata stay in PostgreSQL whatever the store: `odt_collaboration.attachment (id, issue_id, project_id,
   filename, media_type, size, sha256, store, uploaded_by, uploaded_at)`, where `store` is the plugin that holds the
   bytes.
2. **Three plugins**, each in a package of its own with its own configuration, and one chosen by
   `odt.attachments.store`:
   - `database` (default): the bytes in `odt_collaboration.attachment_content (attachment_id, content bytea)`, in the
     transaction of the metadata. Nothing to install; it fits the backups that already cover the database.
   - `filesystem`: files under `odt.attachments.filesystem.root`, written to a temporary file and then moved into
     place, so a half-written file is never read. For a disk or a shared volume.
   - `s3`: objects in `odt.attachments.s3.bucket` (with an optional prefix), through the AWS SDK for Java v2 with
     `endpoint`, `region`, `path-style` and the credentials from the environment, so any S3-compatible server works.
   A plugin that is not chosen creates no bean and needs no setting. At start the back end checks that the chosen
   store can write and read (a bucket that is missing, a folder without rights) and does not start otherwise.
3. **The bytes always pass through the back end**, both ways: the permissions of the issue are checked on each
   download, the session of the BFF is enough, and the store needs no public address. No presigned URLs for now.
4. **API and rules.**
   - `POST /api/issues/{key}/attachments` (multipart, part `file`) needs the new permission `odt.issue.attach`
     (developer and admin of the project).
   - `GET /api/issues/{key}/attachments` lists them for whoever sees the issue.
   - `GET …/attachments/{id}/content` downloads one, always with `Content-Disposition: attachment` and
     `X-Content-Type-Options: nosniff`: a file is never shown inline in the pages of OpenDevTrack.
   - `DELETE …/attachments/{id}` is allowed to the uploader or to an admin of the project.
   - Limits: at most `odt.attachments.max-size` per file (25 MB by default, up to the 100 MB of Quadrava) and 100 files
     per issue. No limit on types (they are only kept and handed back). The SHA-256 is computed while the file is
     received.
   - Each attachment added or removed is a row of the changelog (field `attachments`).
5. **Consistency between the metadata and the bytes.**
   - On upload, the bytes are written first, then the metadata are committed; if the commit fails, the bytes are
     deleted.
   - On delete (one attachment, or all of them when an issue is deleted, from its event), the metadata are removed
     first and the bytes after the commit.
   - A scheduled job removes bytes left without metadata after a failure, once a day, for files older than one day.
   With `database` all of this is one transaction.
6. **Changing the store of an installation is a migration**, not a setting: the attachments already saved stay in the
   store named by their `store` column, and the back end refuses to start if any are kept in a store other than the
   chosen one. A command to move them between stores is a later step.
7. **Screens.** An Attachments tab on `issue-detail`, with a `file` field that uploads at once, and a table with name,
   size, author, date and delete. Downloading from a row needs a request to Quadrava (**Q10**: a column or a row
   action that opens a relative path of the application, as `href` of the `link` component). Until Q10 the API
   downloads and the screen lists.

## Alternatives considered

- **Only the database.** Simplest, but large files weigh on the database, its backups and its replicas. It stays the
  default for small installations.
- **Large objects of PostgreSQL** instead of `bytea`: they stream, but they live outside the rows, need their own
  clean-up (`lo_unlink`) and are not removed by a cascade. With a limit of 25 MB, `bytea` is enough.
- **Three separate artifacts** (one jar per plugin, picked by the classpath). Cleaner for whoever wants none of S3,
  but three builds and three images for one switch. One back end with the three plugins as packages costs only the
  SDK of S3 in the image; the plugins can move to artifacts of their own if a fourth comes.
- **The MinIO client** instead of the AWS SDK. Lighter, but the client of one vendor; the AWS SDK is the reference for
  the S3 API and works with MinIO and the others through `endpoint` and `path-style`.
- **Presigned URLs** for the downloads. They free the back end from the bytes, but a link would work for anyone who
  has it until it expires, and the store would need an address reachable from the browser.

## Consequences

- A migration in `collaboration` (metadata and `attachment_content`) and the permission `odt.issue.attach` in the
  catalogue (ADR-07) and in the project roles developer and admin.
- Dependency on the S3 client of the AWS SDK for Java v2; the tests run the three plugins, S3 on MinIO with
  Testcontainers.
- `spring.servlet.multipart` limits aligned with `odt.attachments.max-size`; an installation behind another proxy
  raises its request limit too.
- Request **Q11** to Quadrava: a setting of the runtime image for the size of a request forwarded to the BFF
  (`client_max_body_size` of nginx), in line with the 100 MB of the `file` field. Until Q11, files up to 1 MB through
  the runtime image (the API of the back end takes up to `odt.attachments.max-size`).
- `ui/`: the Attachments tab of `issue-detail`.
- Request Q10 to Quadrava. Virus scanning and quotas per project are not in this ADR.
