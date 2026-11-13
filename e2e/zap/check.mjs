// Reads the JSON report of the ZAP baseline scan (ADR-23): a medium or high alert fails, unless allowlist.json names
// its plugin id with a reason. Low and informational alerts are listed, never fail.
//
//   node e2e/zap/check.mjs <zap.json> <allowlist.json>
import { readFileSync } from 'node:fs';

const [reportFile, allowFile] = process.argv.slice(2);
const report = JSON.parse(readFileSync(reportFile, 'utf8'));
const allowed = JSON.parse(readFileSync(allowFile, 'utf8')).allowed;
const RISK = { 0: 'info', 1: 'low', 2: 'medium', 3: 'high' };

let failed = false;
const alerts = (report.site || []).flatMap(site => site.alerts || []);
for (const alert of alerts.sort((a, b) => Number(b.riskcode) - Number(a.riskcode))) {
  const risk = Number(alert.riskcode);
  const urls = [...new Set((alert.instances || []).map(i => i.uri))];
  const allow = allowed.find(a => String(a.plugin) === String(alert.pluginid));
  const failing = risk >= 2 && !allow;
  failed ||= failing;
  const state = failing ? 'FAIL' : risk >= 2 ? 'allowed' : 'ok  ';
  console.log(`${state} ${RISK[risk]} ${alert.pluginid} ${alert.name} (${urls.length} URLs: ${urls.slice(0, 3).join(' ')})`);
  if (allow && risk >= 2) {
    console.log(`       ${allow.why}${allow.request ? ` (${allow.request})` : ''}`);
  }
}
console.log(`${alerts.length} kinds of alert, ${alerts.filter(a => Number(a.riskcode) >= 2).length} medium or high`);
process.exit(failed ? 1 : 0);
