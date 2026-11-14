/**
 * Results of the V01 data checks: each check() appends one row to results/checks.json and its raw
 * evidence to results/evidence/<id>.json; summary() writes results/summary.md. No secrets go here.
 */
import * as fs from 'fs';
import * as path from 'path';

export type Status = 'PASS' | 'FAIL' | 'WARN' | 'BLOCKED';
export interface CheckResult {
  id: string; title: string; source: string; method: string; sample: string;
  expected: string; actual: string; status: Status; evidence?: string; next?: string; at?: string;
}

export const RESULTS = path.resolve(__dirname, 'results');
const FILE = path.join(RESULTS, 'checks.json');

function read(): CheckResult[] {
  try { return JSON.parse(fs.readFileSync(FILE, 'utf8')); } catch { return []; }
}

/** Saves raw compared data for a check; returns the relative path to cite as evidence. */
export function evidence(id: string, data: unknown): string {
  const dir = path.join(RESULTS, 'evidence');
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `${id}.json`);
  fs.writeFileSync(file, JSON.stringify(data, null, 2));
  return path.relative(path.resolve(__dirname), file).replace(/\\/g, '/');
}

/** Records (or replaces) one check's result. */
export function check(r: CheckResult) {
  fs.mkdirSync(RESULTS, { recursive: true });
  const rows = read().filter(x => x.id !== r.id);
  rows.push({ ...r, at: new Date().toISOString() });
  rows.sort((a, b) => a.id.localeCompare(b.id, 'en', { numeric: true }));
  fs.writeFileSync(FILE, JSON.stringify(rows, null, 2));
  summary(rows);
}

export function summary(rows = read()) {
  const count = (s: Status) => rows.filter(r => r.status === s).length;
  const lines = [
    '# V01 data checks — staging',
    '',
    `PASS ${count('PASS')} · FAIL ${count('FAIL')} · WARN ${count('WARN')} · BLOCKED ${count('BLOCKED')}`,
    '',
    '| Id | Check | Status | Expected | Actual | Next |',
    '|---|---|---|---|---|---|',
    ...rows.map(r => `| ${r.id} | ${r.title} | ${r.status} | ${r.expected} | ${r.actual} | ${r.next || ''} |`.replace(/\n/g, ' ')),
  ];
  fs.writeFileSync(path.join(RESULTS, 'summary.md'), lines.join('\n') + '\n');
}

/** Runs fn and records BLOCKED (not FAIL) when AWS says the keys may not do it. */
export async function blockedOnDenied(base: Omit<CheckResult, 'status' | 'actual' | 'next'>, fn: () => Promise<void>) {
  try {
    await fn();
  } catch (e: any) {
    if (e && e.action) {
      check({ ...base, status: 'BLOCKED', actual: `AWS denied ${e.action}`, next: `Allow ${e.action} for the check keys (read-only)` });
      return;
    }
    throw e;
  }
}
