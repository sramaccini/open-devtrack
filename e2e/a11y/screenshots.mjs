// The screenshots of the user guide (docs/guide/images, ADR-25): the screens of stack/ with its sample projects, in
// the light theme, 1280 × 800, signed in as a user of the development tenants. Run by stack/stack.sh screenshots.
//
//   node e2e/a11y/screenshots.mjs --front http://localhost:18090 --user mario.rossi --password … --out docs/guide/images
//
// --channel msedge (or chrome) uses a browser installed on the machine instead of the Chromium of Playwright.
import { mkdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { chromium } from 'playwright';

const args = Object.fromEntries(process.argv.slice(2).reduce((pairs, value, i, all) =>
  value.startsWith('--') ? [...pairs, [value.slice(2), all[i + 1]]] : pairs, []));
for (const name of ['front', 'user', 'password', 'out']) {
  if (!args[name]) {
    console.error(`missing --${name}`);
    process.exit(2);
  }
}
const front = args.front.replace(/\/$/, '');
const out = resolve(args.out);
const key = args.project || 'WEB';
const issue = args.issue || 'WEB-4';
mkdirSync(out, { recursive: true });

const browser = await chromium.launch({ channel: args.channel });
const context = await browser.newContext({ viewport: { width: 1280, height: 800 }, locale: 'en-GB',
  colorScheme: 'light' });
const page = await context.newPage();

// Sign in through the runtime and Keycloak, as a person does
await page.goto(`${front}/`);
await page.fill('#username', args.user);
await page.fill('#password', args.password);
await Promise.all([page.waitForURL(`${front}/**`), page.click('#kc-login')]);

const get = (path) => page.evaluate(async (p) => (await fetch(p)).json(), path);
const boards = await get(`/api/projects/${key}/boards`);
const workflows = await get(`/api/projects/${key}/workflows`);
const scrum = boards.find((b) => b.kind === 'scrum') || boards[0];
const kanban = boards.find((b) => b.kind === 'kanban') || boards[0];
const query = encodeURIComponent(
  `project = ${key} AND assignee IS NOT EMPTY AND status != done ORDER BY priority DESC`);

// name, path, and the whole screen (true), the first 800 pixels (false) or a height
const shots = [
  ['dashboard', '/home/dashboard', false],
  ['project-list', '/projects/project-list', false],
  ['project-detail', `/projects/project-detail?key=${key}`, true],
  ['issue-list', `/projects/issue-list?key=${key}`, false],
  ['issue-new', `/projects/issue-new?key=${key}`, false],
  ['issue-detail', `/projects/issue-detail?key=${key}&issue=${issue}`, true],
  ['workflow-detail', `/projects/workflow-detail?key=${key}&workflow=${workflows[0]?.id}`, true],
  ['board-scrum', `/projects/board?board=${scrum?.id}`, false],
  ['board-kanban', `/projects/board?board=${kanban?.id}`, false],
  ['backlog', `/projects/backlog?key=${key}`, true],
  ['search', `/projects/search?q=${query}`, false],
  // The charts leave the page taller than they are: a height of its own
  ['project-dashboard', `/projects/project-dashboard?key=${key}`, 1240],
  ['notification-settings', '/?settings=notification-settings', false],
  ['my-sessions', '/?settings=my-sessions', false],
  ['my-data', '/?settings=my-data', false],
];

let failed = false;
for (const [name, path, fullPage] of shots) {
  if (path.includes('undefined')) {
    console.log(`FAIL ${name}: nothing to open in ${key}`);
    failed = true;
    continue;
  }
  await page.goto(`${front}${path}`);
  await page.waitForLoadState('networkidle');
  await page.waitForSelector('main', { state: 'visible' });
  // The charts and the kanban draw after their data: give them a moment
  await page.waitForTimeout(1500);
  await page.mouse.move(0, 0);
  if (typeof fullPage === 'number') {
    await page.setViewportSize({ width: 1280, height: fullPage });
    await page.waitForTimeout(1000);
  } else if (fullPage) {
    // As tall as the content of the screen: a full-page capture would also take the empty space below it
    // (the visible elements only: the charts keep hidden ones, such as their tooltips, further down)
    const bottom = await page.evaluate(() => Math.max(...[...document.querySelectorAll('main *')]
      .filter((e) => e.checkVisibility({ opacityProperty: true, visibilityProperty: true })
        && e.getBoundingClientRect().height > 0)
      .map((e) => e.getBoundingClientRect().bottom + window.scrollY)));
    await page.setViewportSize({ width: 1280, height: Math.max(800, Math.ceil(bottom) + 24) });
    await page.waitForTimeout(500);
  }
  await page.screenshot({ path: join(out, `${name}.png`) });
  await page.setViewportSize({ width: 1280, height: 800 });
  console.log(`ok   ${name}`);
}
await browser.close();
process.exit(failed ? 1 : 0);
