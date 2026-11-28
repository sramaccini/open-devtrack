# The public API and webhooks

OpenDevTrack can be used from a program as well as from its screens:

- **The REST API** does what the screens do, with the permissions of the user or service account that calls it.
  Its guide is [docs/api.md](../api.md): tokens, service accounts, the tenant, versions, errors and limits. The
  definition is [openapi.json](../openapi.json).
- **Webhooks** tell another system what happens in a project, as it happens.

## Webhooks

The admins of a project manage its webhooks from «Webhooks» on the screen of the project. A project has up to 10.

«New webhook» asks for:

| Field | What |
| --- | --- |
| Name | Unique in the project |
| Address | An `https` address on the internet, for example `https://chat.example.org/hooks/123`. Addresses of private networks are refused, and an installation can also allow only some hosts (in the public demo, only its echo) |
| Events | What it is told about: issue created, changed or deleted, comment posted, attachment added or removed, work logged, changed or deleted, sprint started or completed. «All events» also takes those added in later versions |


A new webhook is active. «Edit» changes these fields and «Active»: when it is off, nothing is queued or posted.

After the creation the screen shows the **signing secret** once. Copy it into the receiver, which uses it to check
that each request comes from OpenDevTrack (Standard Webhooks). It is not shown again.

On each row of the list:

- «Send a test» posts a test event at once.
- «New secret» makes a new secret. For a day the old one signs too, so that the receiver has time to change it.
- «Edit» and «Remove».
- «Deliveries» shows the last 50 deliveries, with their answer, their attempts and their error. «Deliver again» queues a
  delivery once more.

A delivery that fails is tried again after 1, 5 and 30 minutes, then after 2, 6 and 12 hours. A webhook that has
failed for three days is turned off, and the admins of the project get a message. After fixing the receiver, turn it
on again with «Edit».

What a request holds, its headers and how to check its signature are in [docs/api.md](../api.md#webhooks).
