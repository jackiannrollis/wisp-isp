import { createHmac } from 'node:crypto';

import { expect, test } from '@playwright/test';

// Bootstrap is deliberately tested once on a fresh isolated database, not a user's console.
test.describe.configure({ retries: 0 });

function totp(secret: string): string {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = '';
  for (const character of secret.trim()) {
    const value = alphabet.indexOf(character);
    if (value < 0) throw new Error('Invalid test authenticator key');
    bits += value.toString(2).padStart(5, '0');
  }
  const key = Buffer.from((bits.match(/.{8}/g) ?? []).map((byte) => Number.parseInt(byte, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30_000)));
  const hash = createHmac('sha1', key).update(counter).digest();
  const offset = hash[hash.length - 1] & 15;
  return ((hash.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).toString().padStart(6, '0');
}

test('console bootstrap, tenant controls, support handoff and recovery codes work in the browser', async ({
  page,
}, info) => {
  test.setTimeout(120_000);
  const email = 'browser-admin@corebalt.example.test';
  const password = 'fake-browser-verified-password';
  await page.goto('/');
  await page.getByLabel('Email', { exact: true }).fill(email);
  await page.getByLabel('Password', { exact: true }).fill('fake-browser-bootstrap-password');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Choose a new password' })).toBeVisible();
  await page.getByLabel('New password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Save and continue' }).click();
  await expect(page.getByRole('heading', { name: 'Set up your authenticator' })).toBeVisible();
  const secret = (await page.locator('.secret').innerText()).trim();
  await expect(page.locator('.codes li')).not.toHaveCount(0);
  const recoveryCode = (await page.locator('.codes li').first().innerText()).trim();
  await page.getByLabel('Code', { exact: true }).fill(totp(secret));
  await page.getByRole('button', { name: 'Verify', exact: true }).click();
  await expect(page).toHaveURL(/\/tenants$/);
  await expect(page.getByRole('heading', { name: 'Tenants', exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Tenants', exact: true })).toBeVisible();
  await page.screenshot({ path: info.outputPath('console-tenants-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1))
    .toBe(true);
  await page.screenshot({ path: info.outputPath('console-tenants-mobile.png'), fullPage: true });
  await page.setViewportSize({ width: 1280, height: 900 });

  await page.getByRole('button', { name: 'New tenant', exact: true }).click();
  let dialog = page.getByRole('dialog');
  await dialog.getByLabel('Code', { exact: true }).fill('RGR');
  await dialog.getByLabel('Portal subdomain', { exact: true }).fill('browser-regression');
  await dialog.getByLabel('Name', { exact: true }).fill('Browser Regression ISP');
  await dialog.getByLabel("Owner's name", { exact: true }).fill('Fixture Owner');
  await dialog
    .getByLabel("Owner's email", { exact: true })
    .fill('fixture-owner@corebalt.example.test');
  await dialog.getByRole('button', { name: 'Create tenant', exact: true }).click();
  await expect(page.getByLabel('Temporary password', { exact: true })).toHaveText(/\S+/);
  await page.getByRole('button', { name: 'I have kept it', exact: true }).click();
  await expect(page.getByRole('heading', { name: /Browser Regression ISP/ })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Gate 1', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Activate', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Activate', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Activate', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Suspend', exact: true }).click();
  dialog = page.getByRole('dialog');
  await dialog
    .getByLabel('Reason (kept in both audit logs)', { exact: true })
    .fill('Browser regression fixture');
  await dialog.getByRole('button', { name: 'Suspend', exact: true }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Suspended:' })).toBeVisible();
  await page.getByRole('button', { name: 'Reinstate', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Reinstate', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Reinstate', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Open window', exact: true }).click();
  dialog = page.getByRole('dialog');
  await dialog
    .getByLabel('Open until (your time)', { exact: true })
    .fill(new Date(Date.now() + 86_400_000).toISOString().slice(0, 16));
  await dialog.getByRole('button', { name: /Open window|Save/ }).click();
  await expect(page.getByRole('button', { name: 'Close now', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Close now', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Close window', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Open window', exact: true })).toBeVisible();

  await page
    .getByRole('link', { name: /Tenants/ })
    .first()
    .click();
  await page.getByRole('link', { name: 'CB', exact: true }).click();
  const member = page.getByRole('row').filter({ hasText: 'lab-admin@corebalt.example.test' });
  await member.getByRole('button', { name: 'Act as', exact: true }).click();
  dialog = page.getByRole('dialog');
  await dialog.getByLabel('Reason or ticket', { exact: true }).fill('BROWSER-REGRESSION');
  await dialog.getByRole('button', { name: 'Start', exact: true }).click();
  const [admin] = await Promise.all([
    page.waitForEvent('popup'),
    dialog.getByRole('link', { name: /^Open the admin app as/ }).click(),
  ]);
  await expect(admin).toHaveURL(/localhost:4300\/dashboard$/);
  await expect(
    admin.getByRole('status').filter({ hasText: 'Corebalt support acting as' }),
  ).toBeVisible();
  expect(
    await admin.evaluate(() =>
      Object.values(localStorage).some((value) => /^eyJ[A-Za-z0-9_-]+\./.test(value)),
    ),
  ).toBe(false);
  await admin.getByRole('link', { name: 'Settings', exact: true }).click();
  await admin.getByRole('tab', { name: 'SMS account', exact: true }).click();
  await expect(admin.getByRole('button', { name: /^(Save|Connect)$/ })).toHaveCount(0);
  await admin.screenshot({ path: info.outputPath('support-session.png'), fullPage: true });
  await admin.getByRole('button', { name: 'End', exact: true }).click();
  await expect(admin).toHaveURL(/\/impersonation-ended$/);
  await admin.close();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Impersonations', exact: true })).toBeVisible();

  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await page.goto('/tenants');
  await expect(page).toHaveURL(/\/login\?next=/);
  await page.getByLabel('Email', { exact: true }).fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Authenticator code' })).toBeVisible();
  await page.getByLabel('Code', { exact: true }).fill(recoveryCode);
  await page.getByRole('button', { name: 'Verify', exact: true }).click();
  await expect(page).toHaveURL(/\/tenants$/);
  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await page.getByLabel('Email', { exact: true }).fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.getByLabel('Code', { exact: true }).fill(recoveryCode);
  await page.getByRole('button', { name: 'Verify', exact: true }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Authenticator code' })).toBeVisible();
  await page.getByLabel('Code', { exact: true }).fill(totp(secret));
  await page.getByRole('button', { name: 'Verify', exact: true }).click();
  await expect(page).toHaveURL(/\/tenants$/);
  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
});
