import { defineConfig } from '@playwright/test';

/**
 * End-to-end suite (ADR 0012, conventions.md Testing): sign-in, customer search and Pay Now through
 * the isolated e2e stack (infra/e2e/up) with fake payment and SMS providers. All three dev servers
 * use a test-only proxy; an existing developer server is never silently reused.
 */
export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
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
    { name: 'admin', testMatch: /admin\..*\.spec\.ts/, use: { baseURL: 'http://localhost:4300' } },
    {
      name: 'portal',
      testMatch: /portal\..*\.spec\.ts/,
      use: { baseURL: 'http://localhost:4301' },
    },
    {
      name: 'console',
      testMatch: /console\..*\.spec\.ts/,
      use: { baseURL: 'http://localhost:4302' },
    },
  ],
  webServer: [
    {
      command: 'npm run start:admin -- --port 4300 --proxy-config e2e/proxy.conf.cjs',
      url: 'http://localhost:4300',
      reuseExistingServer: false,
      timeout: 180_000,
    },
    {
      command: 'npm run start:portal -- --port 4301 --proxy-config e2e/proxy.conf.cjs',
      url: 'http://localhost:4301',
      reuseExistingServer: false,
      timeout: 180_000,
    },
    {
      command: 'npm run start:console -- --port 4302 --proxy-config e2e/proxy.conf.cjs',
      url: 'http://localhost:4302',
      reuseExistingServer: false,
      timeout: 180_000,
    },
  ],
});
