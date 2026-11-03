import { expect, test } from '@playwright/test';

import { LAB, staffApi } from './support';

test('a customer sets a PIN with a Celcom SMS code and can sign in again', async ({ page }) => {
  const api = await staffApi();
  let customerId: string | undefined;
  try {
    const settings = await api.put('/api/v1/settings/notifications', {
      data: {
        partnerId: '12345',
        senderId: 'COREBALT',
        apiKey: 'fake-browser-celcom-key',
        enabled: true,
        windowStart: '00:00',
        windowEnd: '23:59',
      },
    });
    expect(settings.ok(), await settings.text()).toBeTruthy();
    const phone = '0712349807';
    const created = await api.post('/api/v1/customers', {
      data: { name: 'Browser SMS sign-in fixture', phone },
    });
    expect(created.status()).toBe(201);
    const customer = (await created.json()) as { id: string; number: string };
    customerId = customer.id;
    const messagesBefore = (await (await fetch(`${LAB.fakeDaraja}/__sms`)).json()) as {
      messageId: string;
    }[];
    const previousIds = new Set(messagesBefore.map((message) => message.messageId));

    await page.goto('/');
    await page.getByRole('button', { name: 'Get a code by SMS', exact: true }).click();
    await page.getByLabel('Provider code', { exact: true }).fill(LAB.tenant);
    await page.getByLabel('Phone number', { exact: true }).fill(phone);
    await page.getByRole('button', { name: 'Send code', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Enter the code', exact: true })).toBeVisible();
    const sent = (await (await fetch(`${LAB.fakeDaraja}/__sms`)).json()) as {
      messageId: string;
      mobileTail: string;
      message: string;
    }[];
    const code = sent
      .find(
        (message) =>
          !previousIds.has(message.messageId) &&
          message.mobileTail === phone.slice(-4) &&
          /^\d{6} is your /.test(message.message),
      )
      ?.message.match(/^\d{6}/)?.[0];
    expect(code, 'The fake Celcom provider received the sign-in code').toBeTruthy();
    await page.getByLabel('Code', { exact: true }).fill(code!);
    await page.getByRole('button', { name: 'Continue', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Choose your PIN', exact: true })).toBeVisible();
    await page.getByLabel('New PIN', { exact: true }).fill('5839');
    await page.getByLabel('Repeat the PIN', { exact: true }).fill('5839');
    await page.getByRole('button', { name: 'Save PIN and sign in', exact: true }).click();
    await expect(page).toHaveURL(/\/status$/);
    await expect(page.getByText(customer.number, { exact: true }).first()).toBeVisible();
    await page.reload();
    await expect(page.getByRole('heading', { name: 'My internet', exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await page.getByLabel('Account number', { exact: true }).fill(customer.number);
    await page.getByLabel('PIN', { exact: true }).fill('5839');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page).toHaveURL(/\/status$/);

    const notifications = await (
      await api.get(`/api/v1/notifications?customerId=${customer.id}`)
    ).json();
    const logged = notifications.items.find(
      (item: { templateKey: string }) => item.templateKey === 'SIGN_IN_CODE',
    ) as { body: string } | undefined;
    expect(logged?.body).toContain('******');
    expect(logged?.body).not.toContain(code!);
  } finally {
    if (customerId) {
      const archived = await api.post(`/api/v1/customers/${customerId}/archive`);
      expect(archived.ok(), await archived.text()).toBeTruthy();
    }
    await api.dispose();
  }
});
