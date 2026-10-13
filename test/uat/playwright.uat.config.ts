import { defineConfig, devices } from '@playwright/test';
import * as dotenv from 'dotenv';
import * as path from 'path';

// Credentials live outside the repository: UAT_ENV_FILE points at them.
if (process.env.UAT_ENV_FILE) dotenv.config({ path: process.env.UAT_ENV_FILE });

export default defineConfig({
  testDir: '.',
  testMatch: /.*\.uat\.ts$/,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  outputDir: path.resolve(__dirname, 'results'),
  reporter: [['html', { open: 'never', outputFolder: path.resolve(__dirname, 'report') }], ['list']],
  use: {
    baseURL: process.env.UAT_BASE_URL,
    trace: 'on',
    screenshot: 'on',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    ignoreHTTPSErrors: false,
  },
  projects: [{ name: 'staging', use: { ...devices['Desktop Chrome'] } }],
});
