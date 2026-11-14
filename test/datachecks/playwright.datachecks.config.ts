import { defineConfig, devices } from '@playwright/test';
import * as dotenv from 'dotenv';
import * as path from 'path';

/**
 * V01 read-only data checks against staging. Staging logins from UAT_ENV_FILE (outside the repo);
 * AWS read keys from the repository's .env. Every app call goes through test/uat/guard.ts (GET only),
 * every AWS call through ./aws.ts readOnly().
 */
if (process.env.UAT_ENV_FILE) dotenv.config({ path: process.env.UAT_ENV_FILE });
dotenv.config({ path: path.resolve(__dirname, '..', '..', '.env') });
process.env.UAT_DRY = '1';

export default defineConfig({
  testDir: '.',
  testMatch: /.*\.check\.ts$/,
  timeout: 15 * 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  outputDir: path.resolve(__dirname, 'results-run'),
  reporter: [['list']],
  use: {
    baseURL: process.env.UAT_BASE_URL,
    trace: 'off',
    screenshot: 'only-on-failure',
    actionTimeout: 20_000,
    navigationTimeout: 60_000,
  },
  projects: [{ name: 'staging-datachecks', use: { ...devices['Desktop Chrome'] } }],
});
