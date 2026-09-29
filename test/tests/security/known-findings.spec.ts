import { test, expect } from '@playwright/test';
import { loginAs, creds, hasAdminCreds } from '../../fixtures/auth';

/**
 * Regression checks tied to specific, cited findings in
 * docs/production-readiness-audit-2026-09-14.md (see project doc
 * "04-Security-and-Production-Readiness.md" for a summary). These are
 * intentionally `test.fixme` — they encode the EXPECTED (fixed) behaviour so
 * that once each finding is remediated, flipping fixme -> real assertions
 * turns this file into the regression guard the audit calls for. They are
 * not run by default and must not be treated as currently passing.
 *
 * Each test needs two environments the admin account can see, with distinct
 * IDs known ahead of time — fill in ENV_A_ID / ENV_B_ID below once such a
 * fixture pair exists in your test database.
 */

const ENV_A_ID = process.env.TEST_ENV_A_ID || '';
const ENV_B_ID = process.env.TEST_ENV_B_ID || '';

test.describe('Known production-readiness findings (fixme until remediated)', () => {
  test.skip(!hasAdminCreds() || !ENV_A_ID || !ENV_B_ID,
    'Requires TEST_ADMIN_USERNAME/PASSWORD plus TEST_ENV_A_ID/TEST_ENV_B_ID fixtures');

  test.fixme('finding #10 — audit logs are scoped to the caller\'s actual grants', async ({ page, request }) => {
    // A user with access to ENV_A only must not be able to read ENV_B's audit log.
    await loginAs(page, creds.user.username, creds.user.password);
    const res = await request.get(`/api/v1/audit/logs?environmentId=${ENV_B_ID}`);
    expect(res.status()).toBe(403);
  });

  test.fixme('finding #2 — operation read/cancel is authorized against the execution\'s own environment', async ({ page, request }) => {
    // An execution ID that belongs to ENV_B must 403 when read/cancelled via
    // an ENV_A-authorized session, not just checked against the URL's env.
    await loginAs(page, creds.user.username, creds.user.password);
    // ... create/obtain a real ENV_B execution ID, then:
    // const res = await request.get(`/api/v1/environments/${ENV_A_ID}/operations/{executionId}`);
    // expect(res.status()).toBe(403);
  });

  test.fixme('finding #12 — session-authenticated API mutations require a CSRF token', async ({ page, request }) => {
    await loginAs(page, creds.admin.username, creds.admin.password);
    const res = await request.post(`/api/ec2/instances/i-fake/start`, { data: {} });
    expect(res.status()).not.toBe(200); // should be rejected for missing/invalid CSRF token
  });
});
