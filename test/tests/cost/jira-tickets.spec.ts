import { test, expect, type Page } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E24-T07: "Create Jira ticket" on the Cost Management idle and rightsizing rows. Jira and the
 * two tables are faked in the browser (no real Jira); the rest of the page hits the smoke app.
 * With Jira off no ticket buttons render; with it on a click turns the button into the issue link.
 */

declare const ContentRouter: any;

const IDLE_ROW = {
  vmId: 'vm-idle-1', vmName: 'batch-worker', environmentId: 'env-1', environmentName: 'Dev', groupName: 'jobs',
  monthlyCost: 120, costKnown: true, idleDurationMinutes: 600, idleSince: new Date(Date.now() - 3_600_000).toISOString(),
  latestCpuUtilization: 1.2, monthlyIdleCost: 40.5, jiraKey: null, jiraUrl: null,
};
const RIGHT_ROW = {
  vmId: 'vm-big-1', vmName: 'api-1', environmentId: 'env-1', environmentName: 'Dev', currentInstanceType: 'm5.xlarge',
  suggestedInstanceType: 'm5.large', avgCpuUtilization: 8, peakCpuUtilization: 20, currentMonthlyCost: 140,
  estimatedMonthlyCostAfter: 70, estimatedMonthlySavings: 70, costKnown: true, source: 'HEURISTIC', findingLevel: null,
  direction: 'SCALE_DOWN', vmStatus: 'RUNNING', priceApproximate: false,
  jiraKey: 'OPS-3', jiraUrl: 'https://acme.atlassian.net/browse/OPS-3',
};

function page1(rows: any[]) {
  return JSON.stringify({ content: rows, page: { size: 10, number: 0, totalElements: rows.length, totalPages: 1 } });
}

async function openCostPage(page: Page, jiraEnabled: boolean) {
  await page.route(/\/api\/v1\/integrations\/jira\/status$/, route => route.fulfill({ status: 200, contentType: 'application/json',
    body: JSON.stringify({ enabled: jiraEnabled, configured: jiraEnabled, baseUrl: jiraEnabled ? 'https://acme.atlassian.net' : null }) }));
  await page.route(/\/api\/v1\/cost-management\/idle-waste\?/, route => route.fulfill({ status: 200, contentType: 'application/json', body: page1([IDLE_ROW]) }));
  await page.route(/\/api\/v1\/cost-management\/rightsizing\?/, route => route.fulfill({ status: 200, contentType: 'application/json', body: page1([RIGHT_ROW]) }));
  await signIn(page, 'admin');
  await page.waitForFunction(() => typeof ContentRouter !== 'undefined');
  await page.evaluate(() => { location.hash = '#/cost-management'; });
  await expect(page.locator('#cost-idle-table-body')).toContainText('batch-worker', { timeout: 15_000 });
}

test.describe('Jira ticket buttons', () => {
  test.beforeEach(() => {
    test.skip(!devIds.admin || !canSignIn('admin'), 'Needs TEST_DEV_ADMIN_ID (dev mode)');
  });

  test('an idle row gets a ticket and shows its key; an existing ticket is a link', async ({ page }) => {
    let sent: any = null;
    await page.route(/\/api\/v1\/integrations\/jira\/tickets$/, route => {
      sent = route.request().postDataJSON();
      return route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({
        sourceType: 'IDLE', sourceId: 'vm-idle-1', issueKey: 'OPS-12', url: 'https://acme.atlassian.net/browse/OPS-12', created: true }) });
    });
    await openCostPage(page, true);

    const existing = page.locator('#cost-rightsizing-table-body .jira-link');
    await expect(existing).toHaveText('OPS-3');
    await expect(existing).toHaveAttribute('href', 'https://acme.atlassian.net/browse/OPS-3');
    await expect(existing).toHaveAttribute('rel', 'noopener noreferrer');

    const button = page.locator('#cost-idle-table-body [data-action="jira-create"]');
    await expect(button).toHaveText('Create Jira ticket');
    await button.click();
    await expect(page.locator('#cost-idle-table-body .jira-link')).toHaveText('OPS-12');
    expect(sent).toEqual({ sourceType: 'IDLE', sourceId: 'vm-idle-1' });
  });

  test('with Jira off no ticket buttons or links render', async ({ page }) => {
    await openCostPage(page, false);
    await expect(page.locator('#cost-rightsizing-table-body')).toContainText('api-1');
    await expect(page.locator('[data-action="jira-create"]')).toHaveCount(0);
    await expect(page.locator('.jira-link')).toHaveCount(0);
  });
});
