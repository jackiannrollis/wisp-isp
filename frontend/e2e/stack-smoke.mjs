// Smoke test of the staging/production web front (infra/stack/test.sh, infra/web/Caddyfile): both apps
// are opened through Caddy with its security headers, the lab admin and the lab customer sign in, and
// any browser console error fails the run; a Content-Security-Policy that broke a bundle, a font or
// an inline style would show here first. Usage: node e2e/stack-smoke.mjs https://<domain>[:port]
import { chromium } from '@playwright/test';

const base = new URL(process.argv[2] ?? 'https://corebalt.localhost:8443');
const host = (name) => `${base.protocol}//${name}.${base.host}`;
const errors = [];
const browser = await chromium.launch({ channel: 'chrome' });
const context = await browser.newContext({ ignoreHTTPSErrors: true });
// Expected before sign-in: the guard tries the refresh cookie first and the browser logs that 401
// as a resource error (ADR 0010); everything else counts.
const expected = (m) =>
  /\/session\/refresh$/.test(m.location().url) && m.text().startsWith('Failed to load resource');
context.on(
  'console',
  (m) => m.type() === 'error' && !expected(m) && errors.push(`${m.location().url}: ${m.text()}`),
);
context.on('weberror', (e) => errors.push(`page error: ${e.error().message}`));
const page = await context.newPage();

try {
  await page.goto(host('admin'));
  await page.getByLabel('Organisation code').fill('CB');
  await page.getByLabel('Email').fill('lab-admin@corebalt.example.test');
  await page.getByLabel('Password').fill('lab-admin-password');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.waitForURL(/\/dashboard/, { timeout: 20_000 });
  await page.getByRole('link', { name: 'Customers' }).click();
  await page.getByRole('table').getByRole('row').nth(1).waitFor({ timeout: 20_000 });
  console.log('   ok   admin: signed in, dashboard and customers rendered');

  await page.goto(host('cb'));
  await page.getByLabel('Account number').fill('CB000001');
  await page.getByLabel('PIN').fill('4071');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.waitForURL(/\/status/, { timeout: 20_000 });
  await page.getByText('Wallet balance').waitFor({ timeout: 20_000 });
  await page.getByText('Corebalt Lab ISP').first().waitFor({ timeout: 10_000 });
  console.log('   ok   portal on a tenant subdomain: signed in, branding and status rendered');
} catch (e) {
  errors.push(`flow: ${e.message.split('\n')[0]}`);
} finally {
  await browser.close();
}
if (errors.length) {
  console.log('   FAIL browser errors:');
  for (const e of errors) console.log('        ' + e);
  process.exit(1);
}
