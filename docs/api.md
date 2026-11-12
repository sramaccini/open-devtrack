# The OpenDevTrack API

OpenDevTrack has one REST API, `/api`: the one its screens use (ADR-21). This page is for scripts, integrations and
other services that call it. The definition, OpenAPI 3.1, is [openapi.json](openapi.json); an instance serves the
same at `/v3/api-docs` to any signed-in user. Open it with any OpenAPI viewer: the instance has no Swagger UI.

## Versions and stability

- The API is version 1.0. Within 1.x no public path or field is removed or renamed: one may be marked `deprecated`
  and removed in the next major version. New paths and new fields may come at any time, so a client ignores the
  fields it does not know.
- Paths marked `x-internal: true` serve only a screen (the counts of the personal dashboard, the suggestions of a
  field, the fields of a form): they work the same, but may change without notice.
- `docs/openapi.json` is generated from the code and checked by a test of the build (`OpenApiContractTest`): every
  change of the API shows up in review as a change of that file.

## Authentication

A client sends a Keycloak access token of the realm of the installation:

```http
Authorization: Bearer eyJhbGciOi...
```

- The token says who the user is; the roles and permissions are those of a session of that user (authz-library),
  resolved again at each request. No session is created.
- Only tokens issued to the clients the installation allows are accepted (their `azp`): `ODT_BEARER_CLIENTS`, by
  default `opendevtrack-tools`.
- A request with both the session cookie and a token is refused (400). Requests with a token need no CSRF token.
- The browser never holds a token: the screens use the session cookie of the BFF.

### A person

Their own token, from a client that gives one to a person. In development and in the stack, `opendevtrack-tools`
(public, password grant):

```bash
TOKEN=$(curl -s http://localhost:18180/realms/opendevtrack/protocol/openid-connect/token \
  -d grant_type=password -d client_id=opendevtrack-tools \
  -d username=mario.rossi --data-urlencode "password=$PASSWORD" | jq -r .access_token)
```

### A service account

For a machine (a CI job, a chat bot, a sync with another tool):

1. In Keycloak, a confidential client with only **Service accounts roles** (client credentials) enabled, for example
   `acme-ci`. Its user is `service-account-acme-ci`.
2. The client is added to `ODT_BEARER_CLIENTS` of the installation.
3. An admin of the tenant adds `service-account-acme-ci` as a user of the tenant, with its roles (as any user, in
   the RBAC administration of authz-library), and an admin of each project adds it as a member with a project role.

```bash
TOKEN=$(curl -s http://localhost:18180/realms/opendevtrack/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=opendevtrack-ci \
  --data-urlencode "client_secret=$CI_CLIENT_SECRET" | jq -r .access_token)
```

The stack and the e2e environment have such a client, `opendevtrack-ci` (its secret is `CI_CLIENT_SECRET` in
`.work/.env`), whose user is a member of the tenant `ACME`.

### The tenant

A user may work in several tenants. The header `X-Tenant` picks one; without it the request works on the tenant the
user last used. A tenant the user has no role in answers 403.

```bash
curl -s -H "Authorization: Bearer $TOKEN" -H "X-Tenant: ACME" http://localhost:18080/api/projects
```

## Permissions

Each operation of the definition lists what it needs:

- `x-permissions`: permissions of the user in the tenant (`odt.project.browse`, `odt.project.create`…);
- `x-project-permissions`: permissions of the role of the user in the project of the request (`odt.issue.edit`,
  `odt.sprint.manage`…). Without them the answer is 403; a project the user does not see answers 404.

The answers carry `_perm`: what the user may do with that row or form (`{"edit": true, "delete": false}`), so a
client need not compute the roles.

## Writing: versions and 412

A resource that can change has a `version`. A change sends the `version` it read; if someone changed it meanwhile the
answer is **412** and the client reads it again:

```bash
curl -s -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  http://localhost:18080/api/issues/WEB-42 \
  -d '{"summary": "Crash when saving a draft", "type": "bug", "priority": "high", "version": 7}'
```

Issues change status only through the transitions of their workflow: `GET /api/issues/{key}/transitions`, then
`POST /api/issues/{key}/transitions` with `{"transition": "start", "version": 7}`.

## Lists and the search

- Lists are not paged unless they say so; each has a limit: the issues of a project at most 500 (newest first),
  the history of an issue at most its last 500 changes.
- `POST /api/search` runs an ODQL query (ADR-16) and pages: `page` from 0, `size` up to 100 (25 by default), with
  `total` in the answer.

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  http://localhost:18080/api/search -d '{"query": "project = WEB AND status != done ORDER BY updated DESC", "size": 50}'
```

## Errors

Errors are Problems (RFC 9457, `application/problem+json`) with an English `title`:

| Status | When |
| --- | --- |
| 400 | The request is not valid; `errors` lists the fields, `path` a JSON Pointer into the body and `code` what is wrong |
| 401 | No token, or one not valid, expired or of a client not allowed |
| 403 | The user may not do it, or the tenant of `X-Tenant` is not theirs |
| 404 | Not found, or in a project of another tenant or one the user does not see |
| 409 | It conflicts with the state of something else (a key already used) |
| 412 | The `version` sent is not the current one |
| 429 | Too many requests (below) |

```json
{ "type": "about:blank", "title": "Summary is required", "status": 400,
  "errors": [{ "path": "/summary", "code": "required" }] }
```

## Limits

Requests with a token are limited per user or service account: 600 a minute by default (authz-library, counted on
Redis). Past it the answer is 429 with `Retry-After` in seconds.
