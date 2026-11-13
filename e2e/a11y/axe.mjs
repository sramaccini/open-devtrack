// Accessibility check of the main screens of OpenDevTrack (ADR-23): axe-core in a headless browser, signed in as a
// user of the development tenants. A serious or critical violation fails, unless allowlist.json names it with the
// request that fixes it (Quadrava or ui/). A violation of the Content-Security-Policy of the runtime image, reported
// by the browser on any screen, fails too: the policy must not break what the screens draw.
//
//   node e2e/a11y/axe.mjs --front http://localhost:28090 --user mario.rossi --password … --project KEY
//
// --channel msedge (or chrome) uses a browser installed on the machine instead of the Chromium of Playwright.
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { AxeBuilder } from '@axe-core/playwright';

const here = dirname(fileURLToPath(import.meta.url));
const args = Object.fromEntries(process.argv.slice(2).reduce((pairs, value, i, all) =>
  value.startsWith('--') ? [...pairs, [value.slice(2), all[i + 1]]] : pairs, []));
for (const name of ['front', 'user', 'password', 'project']) {
  if (!args[name]) {
    console.error(`missing --${name}`);
    process.exit(2);
  }
}
const front = args.front.replace(/\/$/, '');
const key = args.project;
const allowlist = JSON.parse(readFileSync(join(here, 'allowlist.json'), 'utf8')).allowed;
const FAILING = new Set(['serious', 'critical']);

const browser = await chromium.launch({ channel: args.channel });
const context = await browser.newContext({ viewport: { width: 1280, height: 800 }, locale: 'en-GB' });
const page = await context.newPage();
const cspViolations = [];
page.on('console', (message) => {
  if (/Content Security Policy/i.test(message.text())) {
    cspViolations.push(message.text());
  }
});

// Sign in through the runtime and Keycloak, as a person does
await page.goto(`${front}/`);
await page.fill('#username', args.user);
await page.fill('#password', args.password);
await Promise.all([page.waitForURL(`${front}/**`), page.click('#kc-login')]);

const boards = await page.evaluate(async (k) => (await fetch(`/api/projects/${k}/boards`)).json(), key);
const issue = await page.evaluate(async (k) => {
  const rows = await (await fetch('/api/search', {
    method: 'POST', headers: { 'Content-Type': 'application/json',
      'X-XSRF-TOKEN': decodeURIComponent((document.cookie.match(/XSRF-TOKEN=([^;]+)/) || [])[1] || '') },
    body: JSON.stringify({ query: `project = ${k}`, page: 0, size: 1 }),
  })).json();
  return rows.items[0]?.key;
}, key);

const screens = [
  ['dashboard', '/home/dashboard'],
  ['project-list', '/projects/project-list'],
  ['project-detail', `/projects/project-detail?key=${key}`],
  ['issue-list', `/projects/issue-list?key=${key}`],
  ['issue-detail', issue ? `/projects/issue-detail?key=${key}&issue=${issue}` : null],
  ['issue-new', `/projects/issue-new?key=${key}`],
  ['board', boards[0] ? `/projects/board?board=${boards[0].id}` : null],
  ['backlog', `/projects/backlog?key=${key}`],
  ['search', '/projects/search'],
  ['notification-settings', '/projects/notification-settings'],
  ['webhook-list', `/projects/webhook-list?key=${key}`],
];

let failed = false;
const report = [];
for (const [screen, path] of screens) {
  if (!path) {
    console.log(`FAIL ${screen}: nothing to open (no issue or no board in ${key})`);
    failed = true;
    continue;
  }
  await page.goto(`${front}${path}`);
  await page.waitForLoadState('networkidle');
  // The screen is drawn when its main region has content and no table or form is still busy
  await page.waitForSelector('main', { state: 'visible' });
  await page.waitForTimeout(500);
  const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
  const serious = result.violations.filter(v => FAILING.has(v.impact));
  const open = serious.filter(v => !allowlist.some(a => a.rule === v.id && (!a.screens || a.screens.includes(screen))));
  report.push({ screen, path, violations: result.violations.map(v => ({ rule: v.id, impact: v.impact, nodes: v.nodes.length,
    help: v.help, targets: v.nodes.slice(0, 5).map(n => n.target.join(' ')) })) });
  const csp = cspViolations.splice(0);
  const line = `${screen}: ${result.violations.length} violations, ${serious.length} serious or critical, ${open.length} not allowed, ` +
    `${csp.length} CSP violations`;
  if (open.length || csp.length) {
    failed = true;
    console.log(`FAIL ${line}`);
    for (const v of open) {
      console.log(`     ${v.impact} ${v.id}: ${v.help} (${v.nodes.length}): ${v.nodes.slice(0, 3).map(n => n.target.join(' ')).join(' | ')}`);
    }
    for (const text of csp.slice(0, 3)) {
      console.log(`     csp: ${text}`);
    }
  } else {
    console.log(`ok   ${line}`);
  }
}
await browser.close();
if (args.report) {
  writeFileSync(args.report, JSON.stringify(report, null, 2) + '\n');
}
process.exit(failed ? 1 : 0);
