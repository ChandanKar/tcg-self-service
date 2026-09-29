import { defineConfig, devices } from '@playwright/test';
import * as dotenv from 'dotenv';
import * as path from 'path';

// Load test/.env (falls back silently to process env / defaults if absent).
dotenv.config({ path: path.resolve(__dirname, '.env') });

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

export default defineConfig({
  testDir: './tests',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  fullyParallel: false, // the app has a single dev DB + environment locks; avoid cross-test contention
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [
    ['html', { open: 'never' }],
    ['list'],
  ],
  use: {
    baseURL: BASE_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 10_000,
    navigationTimeout: 15_000,
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  // Uncomment to have Playwright start the app itself (requires a working
  // .env at the repo root with a reachable MySQL instance — see
  // test/README.md "Running against a live server").
  // webServer: {
  //   command: 'cd .. && ./gradlew bootRun',
  //   url: BASE_URL,
  //   timeout: 120_000,
  //   reuseExistingServer: true,
  // },
});
