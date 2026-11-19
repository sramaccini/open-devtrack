# The Keycloak theme of OpenDevTrack

`themes/opendevtrack` is the login theme of the realm of OpenDevTrack (ADR-24 point 2, open item O5, ASVS 2.1.8). It
extends `keycloak.v2`, the login theme of Keycloak 26, with:

- the logo of [brand/](../brand/README.md) in the header, the light or dark variant as the page is, and the brand
  colours on buttons, links and fields;
- a password strength meter on the pages that choose a password (registration, update, reset): `resources/js/strength.js`
  scores length and variety and flags the commonest passwords from a short list in the script. It advises only: the
  password policy of the realm is what refuses a password ([asvs.md](../docs/security/asvs.md), «The installation»).
  English and Italian, from the language of the page. No library, nothing sent anywhere.

## Installing it

The images are the files of `brand/` as they are, never copied into the theme. An installation puts together:

| From | To, in the Keycloak container |
| --- | --- |
| `keycloak/themes/opendevtrack/login/theme.properties` | `/opt/keycloak/themes/opendevtrack/login/theme.properties` |
| `keycloak/themes/opendevtrack/login/resources/css`, `…/js` | `/opt/keycloak/themes/opendevtrack/login/resources/css`, `…/js` |
| `brand/` | `/opt/keycloak/themes/opendevtrack/login/resources/brand` |
| `brand/favicon.ico` | `/opt/keycloak/themes/opendevtrack/login/resources/img/favicon.ico` |

The compose files of `e2e/authz` and `stack` mount them so, and the realm `realm-opendevtrack.json` sets
`"loginTheme": "opendevtrack"`. For an existing realm: *Realm settings → Themes → Login theme*.

Keycloak caches themes outside `start-dev`: after a change, restart it or clear the cache
(`--spi-theme--cache-themes=false` only while developing).

## Checks

`e2e/authz/run.sh check` reads the login page and checks the stylesheet, the script and the logo of `brand/`. The look
and the meter were checked by hand on Keycloak 26.8.0 in light and dark mode on 19 November 2026.
