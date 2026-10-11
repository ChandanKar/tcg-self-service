import { test, expect } from '@playwright/test';
import { signIn, canSignIn, devIds } from '../../fixtures/auth';

/**
 * E25-T04: a start that needs approval comes back as "held" (202 with approvalRequestId). The
 * progress dialog closes and the user is told who decides and why; nothing is polled.
 */

declare const VmOperations: any;

test.describe('Start held for approval', () => {
  test.beforeEach(() => {
    test.skip(!devIds.user || !canSignIn('user'), 'Needs TEST_DEV_USER_ID (dev mode)');
  });

  test('the user is told it waits for approval and nothing is tracked', async ({ page }) => {
    let polls = 0;
    await page.route(/\/api\/v1\/environments\/env-held\/operations(\/.*)?(\?.*)?$/, route => {
      if (route.request().method() === 'POST') {
        return route.fulfill({ status: 202, contentType: 'application/json', body: JSON.stringify({
          approvalRequestId: 'req-1', status: 'PENDING_APPROVAL', reasons: ['8 h is longer than 1 h'],
          approvers: ['Alice', 'Bob'], estimatedCost: 1.54 }) });
      }
      polls++;
      return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    });
    await signIn(page, 'user');
    await page.waitForFunction(() => typeof VmOperations !== 'undefined');

    const result = await page.evaluate(() => VmOperations.startEnvironment('env-held', 'Held env').then((r: any) => r.approvalRequestId));
    expect(result).toBe('req-1');
    await expect(page.getByText('Sent for approval to Alice, Bob. It starts once approved. Why: 8 h is longer than 1 h.'))
      .toBeVisible();
    await expect(page.locator('#operationProgressModal.show')).toHaveCount(0);
    await page.waitForTimeout(1500);
    expect(polls).toBe(0);
  });
});
