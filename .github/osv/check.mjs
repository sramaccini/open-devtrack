// Reads the JSON report of OSV-Scanner (ADR-23): a known vulnerability of high or critical severity (CVSS 7.0 or more)
// fails, unless osv-scanner.toml ignores it with a reason (OSV-Scanner leaves those out of the report). The others are
// listed and do not fail: Dependabot opens their updates.
//
//   node .github/osv/check.mjs <osv.json>
import { readFileSync } from 'node:fs';

const report = JSON.parse(readFileSync(process.argv[2], 'utf8'));
let failed = false;
let count = 0;
for (const result of report.results || []) {
  for (const entry of result.packages || []) {
    const name = `${entry.package.ecosystem}:${entry.package.name}@${entry.package.version}`;
    for (const group of entry.groups || []) {
      count++;
      const score = Number.parseFloat(group.max_severity);
      const high = !Number.isNaN(score) && score >= 7;
      failed ||= high;
      console.log(`${high ? 'FAIL' : 'ok  '} ${Number.isNaN(score) ? 'n/a' : score.toFixed(1)} ${name} ${group.ids.join(', ')}`);
    }
  }
}
console.log(`${count} known vulnerabilities, ${failed ? 'some' : 'none'} high or critical`);
process.exit(failed ? 1 : 0);
