import { defineConfig } from '@playwright/test';

/**
 * End-to-end suite (ADR 0012, conventions.md Testing): sign-in, customer search and Pay Now through
 * the real backend, against the e2e stack (infra/e2e/up) with its fake Daraja. Both dev servers are
 * started here, or reused when they already run (local development).
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env['CI'],
  retries: process.env['CI'] ? 1 : 0,
  reporter: process.env['CI'] ? [['list'], ['html', { open: 'never' }]] : 'list',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  use: {
    // The installed Chrome (GitHub's Ubuntu runners have it): no browser download.
    channel: 'chrome',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'admin', testMatch: /admin\..*\.spec\.ts/, use: { baseURL: 'http://localhost:4200' } },
    {
      name: 'portal',
      testMatch: /portal\..*\.spec\.ts/,
      use: { baseURL: 'http://localhost:4201' },
    },
  ],
  webServer: [
    {
      command: 'npm run start:admin',
      url: 'http://localhost:4200',
      reuseExistingServer: !process.env['CI'],
      timeout: 180_000,
    },
    {
      command: 'npm run start:portal',
      url: 'http://localhost:4201',
      reuseExistingServer: !process.env['CI'],
      timeout: 180_000,
    },
  ],
});
