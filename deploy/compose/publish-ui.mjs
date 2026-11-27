// Publishes the screens of ui/ to the registry of the back end, environment production (./odt.sh publish-ui). The
// token is the one of the service account opendevtrack-publisher (client credentials), whose user has the role
// ODT_UI_AUTHOR (./odt.sh first-admin); the Quadrava CLI reads it from QUADRAVA_TOKEN, never from the command line.
import { spawnSync } from 'node:child_process';

const realm = `${process.env.KEYCLOAK_URL}/realms/${process.env.KEYCLOAK_REALM}`;
const answer = await fetch(`${realm}/protocol/openid-connect/token`, {
  method: 'POST',
  body: new URLSearchParams({
    grant_type: 'client_credentials',
    client_id: process.env.PUBLISHER_CLIENT_ID,
    client_secret: process.env.PUBLISHER_CLIENT_SECRET,
  }),
});
if (!answer.ok) {
  console.error(`No token for ${process.env.PUBLISHER_CLIENT_ID} from ${realm}: ${answer.status}`);
  process.exit(1);
}
const { access_token: token } = await answer.json();

const cli = spawnSync('npx', ['--yes', '@sramaccini/quadrava-cli@0.29.0', 'publish', 'ui',
  '--registry', 'http://backend:8080', '--app', 'opendevtrack', '--env', 'production'],
  { stdio: 'inherit', env: { ...process.env, QUADRAVA_TOKEN: token } });
process.exit(cli.status ?? 1);
