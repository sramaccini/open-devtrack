// Publishes the Quadrava project in ui/ to the registry of OpenDevTrack (contracts/registry.yaml 0.6), one document at
// a time: save a version, then point the environment at it. Temporary, until the CLI of Quadrava 0.2.0
// (`quadrava publish`, request Q2) replaces it.
//
//   node tools/publish-ui.mjs --registry http://localhost:8080 --token <access token> [--env development]
//        [--reviewer-token <access token>]     protected environments: request the review as author, approve as reviewer
//
// The tokens are Keycloak access tokens of the client opendevtrack-tools (backend/README.md). Data sources are published
// before the screens that use them and screens before the navigation, so a protected environment accepts each one.
// Exits with code 1 at the first refusal, printing the Problem of the registry.
import { readFile, readdir } from 'node:fs/promises';
import { basename, join } from 'node:path';
import { parseArgs } from 'node:util';

const { values: args } = parseArgs({
  options: {
    registry: { type: 'string', default: 'http://localhost:8080' },
    token: { type: 'string' },
    'reviewer-token': { type: 'string' },
    env: { type: 'string', default: 'development' },
    project: { type: 'string', default: join(import.meta.dirname, '..', 'ui') },
  },
});
if (!args.token) {
  console.error('--token is required');
  process.exit(2);
}

const root = args.project;
const project = JSON.parse(await readFile(join(root, 'project.json'), 'utf8'));
const app = project.project;
const registry = args.registry.replace(/\/+$/, '');

async function call(method, path, token, body) {
  const response = await fetch(`${registry}${path}`, {
    method,
    headers: { Authorization: `Bearer ${token}`, Accept: 'application/json', ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body),
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`${method} ${path}: ${response.status} ${text}`);
  }
  return text ? JSON.parse(text) : null;
}

async function jsonFiles(folder) {
  try {
    return (await readdir(join(root, folder))).filter((f) => f.endsWith('.json')).sort().map((f) => join(folder, f));
  } catch {
    return [];
  }
}

/** The documents of the project with their kind and identifier, in publication order. */
async function documents() {
  const list = [];
  for (const file of await jsonFiles('datasources')) list.push({ kind: 'datasources', file, content: await readFile(join(root, file), 'utf8') });
  for (const file of await jsonFiles('themes')) list.push({ kind: 'theme', file, content: await readFile(join(root, file), 'utf8') });
  for (const file of await jsonFiles('i18n')) {
    // The catalogue of a language is a flat key → text map in the project, a messages document in the registry.
    const locale = basename(file, '.json');
    const texts = JSON.parse(await readFile(join(root, file), 'utf8'));
    list.push({ kind: 'messages', file, content: JSON.stringify({ schemaVersion: '1.0', messages: locale, texts }, null, 2) + '\n' });
  }
  for (const file of await jsonFiles('screens')) list.push({ kind: 'screen', file, content: await readFile(join(root, file), 'utf8') });
  list.push({ kind: 'navigation', file: 'navigation.json', content: await readFile(join(root, 'navigation.json'), 'utf8') });
  const field = { datasources: 'datasources', theme: 'theme', messages: 'messages', screen: 'screen', navigation: 'navigation' };
  return list.map((d) => ({ ...d, id: JSON.parse(d.content)[field[d.kind]] }));
}

try {
  for (const doc of await documents()) {
    const base = `/registry/apps/${app}/documents/${doc.kind}/${doc.id}`;
    const version = await call('POST', `${base}/versions`, args.token, doc.content);
    if (args['reviewer-token'] && version.state !== 'approved') {
      if (version.state !== 'in-review') await call('POST', `${base}/versions/${version.hash}/review`, args.token, { action: 'request' });
      await call('POST', `${base}/versions/${version.hash}/review`, args['reviewer-token'], { action: 'approve' });
    }
    await call('PUT', `${base}/published?env=${encodeURIComponent(args.env)}`, args.token, { hash: version.hash });
    console.log(`ok   ${doc.kind.padEnd(11)} ${doc.id.padEnd(16)} ${version.hash.slice(0, 12)} → ${args.env}`);
  }
} catch (e) {
  console.error(`FAIL ${e.message}`);
  process.exit(1);
}
