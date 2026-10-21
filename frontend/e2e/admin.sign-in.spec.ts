import { expect, test } from '@playwright/test';

import { adminSignIn } from './support';

test('ADR0010_staffSignInSurvivesAReloadAndSignOutEndsIt', async ({ page }) => {
  await adminSignIn(page);
  await expect(page).toHaveTitle(/Dashboard/);

  // The access token lives in memory only: a reload signs in again from the refresh cookie.
  await page.reload();
  await expect(page).toHaveURL(/\/dashboard/);
  await expect(page).toHaveTitle(/Dashboard/);

  await page.getByRole('button', { name: 'Account' }).click();
  await page.getByRole('menuitem', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login/);

  await page.goto('/customers');
  await expect(page).toHaveURL(/\/login\?next=%2Fcustomers/);
});
