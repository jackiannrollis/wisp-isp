import { expect, test } from '@playwright/test';

import { LAB, adminSignIn, staffApi } from './support';

test('Celcom account saves without exposing its key and payment delivery is confirmed', async ({
  page,
}, info) => {
  await adminSignIn(page);
  await page.goto('/settings');
  await page.getByRole('tab', { name: 'SMS account', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: /Celcom Africa account|Connect Celcom Africa/ }),
  ).toBeVisible();
  await page.getByLabel('Partner ID', { exact: true }).fill('12345');
  await page.getByLabel('Sender ID', { exact: true }).fill('COREBALT');
  await page.getByLabel('API key', { exact: true }).fill('fake-browser-celcom-key');
  await page.getByRole('button', { name: /^(Save|Connect)$/ }).click();
  await expect(page.getByText('SMS settings saved.', { exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Celcom Africa account' })).toBeVisible();
  await expect(page.getByLabel('API key', { exact: true })).toHaveValue('');
  await expect(page.getByLabel('API key', { exact: true })).toHaveAttribute(
    'placeholder',
    'Saved; type to replace',
  );
  await expect(page.getByText('Delivery is checked automatically', { exact: false })).toBeVisible();
  await page.reload();
  await page.getByRole('tab', { name: 'SMS account', exact: true }).click();
  await expect(page.getByLabel('Partner ID', { exact: true })).toHaveValue('12345');
  await expect(page.getByLabel('API key', { exact: true })).toHaveValue('');
  await page.screenshot({ path: info.outputPath('celcom-settings-desktop.png'), fullPage: true });

  const api = await staffApi();
  try {
    const customers = await (await api.get(`/api/v1/customers?q=${LAB.customer}`)).json();
    const customerId: string = customers.items[0].id;
    const before = await (
      await api.get(`/api/v1/notifications?customerId=${customerId}&size=200`)
    ).json();
    const beforeIds = new Set(before.items.map((item: { id: string }) => item.id));
    const response = await api.post('/api/v1/payments/manual', {
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      data: {
        customerId,
        amount: { amount: 100, currency: 'KES' },
        channel: 'CASH',
        reference: `BROWSER-${crypto.randomUUID()}`,
        note: 'Fake browser-test payment',
      },
    });
    expect(response.status()).toBe(201);
    await expect
      .poll(
        async () => {
          const notifications = await (
            await api.get(`/api/v1/notifications?customerId=${customerId}&size=200`)
          ).json();
          return notifications.items.find(
            (item: { id: string; templateKey: string; status: string }) =>
              !beforeIds.has(item.id) && item.templateKey.startsWith('PAYMENT_RECEIVED'),
          )?.status;
        },
        { timeout: 30_000 },
      )
      .toBe('DELIVERED');
    const sent = (await (await fetch(`${LAB.fakeDaraja}/__sms`)).json()) as {
      deliveryChecks: number;
    }[];
    expect(sent.some((message) => message.deliveryChecks > 0)).toBe(true);
  } finally {
    await api.dispose();
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await page.evaluate(() => window.dispatchEvent(new Event('resize')));
  await expect(page.locator('mat-sidenav')).not.toHaveClass(/mat-drawer-opened/);
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1))
    .toBe(true);
  const partnerId = page.getByLabel('Partner ID', { exact: true });
  await expect(partnerId).toBeVisible();
  await expect
    .poll(async () => {
      const rect = await partnerId.boundingBox();
      return rect ? Math.min(rect.width, rect.x + rect.width) : 0;
    })
    .toBeGreaterThan(200);
  await page.screenshot({ path: info.outputPath('celcom-settings-mobile.png'), fullPage: true });
});
