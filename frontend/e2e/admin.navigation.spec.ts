import { expect, test } from '@playwright/test';

import { adminSignIn } from './support';

test('all main admin pages load without uncaught errors or failed server responses', async ({
  page,
}, info) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('response', (response) => {
    if (response.url().includes('/api/') && response.status() >= 500)
      errors.push(`${response.status()} ${new URL(response.url()).pathname}`);
  });
  await adminSignIn(page);
  for (const path of [
    '/customers',
    '/subscriptions',
    '/packages',
    '/payments',
    '/network',
    '/sessions',
    '/alerts',
    '/staff',
    '/audit-log',
    '/settings',
    '/dashboard',
  ]) {
    await page.goto(path);
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`${path}$`));
  }
  expect(errors).toEqual([]);
  await page.screenshot({ path: info.outputPath('admin-dashboard-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.evaluate(() => window.dispatchEvent(new Event('resize')));
  await expect(page.getByRole('button', { name: 'Reload', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Open navigation', exact: true })).toBeVisible();
  await expect(page.locator('mat-sidenav')).not.toHaveClass(/mat-drawer-opened/);
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1))
    .toBe(true);
  await expect
    .poll(() =>
      page
        .locator('.queue-title')
        .first()
        .evaluate((e) => e.clientWidth),
    )
    .toBeGreaterThan(45);
  await page.screenshot({ path: info.outputPath('admin-dashboard-mobile.png'), fullPage: true });
});
