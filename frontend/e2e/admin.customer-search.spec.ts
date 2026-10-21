import { expect, test } from '@playwright/test';

import { LAB, adminSignIn } from './support';

test('customer search runs on the server by number, name or phone', async ({ page }) => {
  await adminSignIn(page);
  await page.goto('/customers');
  const search = page.getByRole('searchbox', { name: 'Search number, name or phone' });
  const table = page.getByRole('table');

  // conventions.md Tables: the query goes to the API, never filtered in the browser.
  const byNumber = page.waitForResponse(
    (r) => r.url().includes('/api/v1/customers?') && r.url().includes(`q=${LAB.customer}`),
  );
  await search.fill(LAB.customer);
  await search.press('Enter');
  expect((await byNumber).ok()).toBeTruthy();
  await expect(table.getByRole('row').filter({ hasText: LAB.customer })).toHaveCount(1);

  await search.fill('Lab Customer');
  await search.press('Enter');
  await expect(table.getByRole('row').filter({ hasText: 'Lab Customer' }).first()).toBeVisible();

  await search.fill('nobody-by-this-name');
  await search.press('Enter');
  await expect(page.getByText('Try another search or fewer filters.')).toBeVisible();
});
