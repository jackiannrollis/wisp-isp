import { expect, test } from '@playwright/test';

import { LAB, portalSignIn } from './support';

test('ADR0010_customerSignsInWithAccountAndPinAndStaysSignedInOnReload', async ({ page }) => {
  await portalSignIn(page);
  await expect(page.getByRole('heading', { name: 'My internet' })).toBeVisible();
  await expect(page.getByText(LAB.customer).first()).toBeVisible();

  await page.reload();
  await expect(page).toHaveURL(/\/status/);
  await expect(page.getByText('Wallet balance')).toBeVisible();

  await page.getByRole('link', { name: 'History', exact: true }).click();
  await expect(page.getByRole('tab', { name: 'Payments' })).toBeVisible();

  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login/);
  await page.goto('/history');
  await expect(page).toHaveURL(/\/login\?next=%2Fhistory/);
});
