import { APIRequestContext, expect, test } from '@playwright/test';

import { LAB, portalSignIn, staffApi } from './support';

interface Line {
  id: string;
  pppoeUsername: string;
  packageId: string;
  routerId: string;
  state: string;
  accessProfile: string;
}

let staff: APIRequestContext;
let line: Line | null = null;

test.beforeAll(async () => {
  // Pay Now goes through Daraja; this suite only ever talks to the fake one (ADR 0012).
  const fake = await fetch(`${LAB.fakeDaraja}/health`).catch(() => null);
  expect(
    fake?.ok,
    'Start the isolated stack with infra/e2e/up: Pay Now needs the fake Daraja.',
  ).toBeTruthy();

  staff = await staffApi();
  // Obvious fakes; the fake Daraja accepts any. The Daraja sandbox script sets its own again.
  const mpesa = await staff.put('/api/v1/settings/mpesa', {
    data: {
      shortcode: '600000',
      shortcodeType: 'PAYBILL',
      sandbox: true,
      consumerKey: 'fake-e2e-consumer-key',
      consumerSecret: 'fake-e2e-consumer-secret',
      passkey: 'fake-e2e-passkey',
    },
  });
  expect(mpesa.ok(), await mpesa.text()).toBeTruthy();

  // A second line for the lab customer: PENDING, so due, and not FULL, so the page waits for it.
  // One left by an interrupted run is used again.
  const customers = await (await staff.get(`/api/v1/customers?q=${LAB.customer}`)).json();
  const customerId: string = customers.items[0].id;
  const lines = (await (await staff.get(`/api/v1/subscriptions?customerId=${customerId}`)).json())
    .items as Line[];
  line = lines.find((l) => l.state === 'PENDING') ?? null;
  if (!line) {
    const opened = await staff.post('/api/v1/subscriptions', {
      data: { customerId, packageId: lines[0].packageId, routerId: lines[0].routerId },
    });
    expect(opened.status()).toBe(201);
    line = ((await opened.json()) as { subscription: Line }).subscription;
  }
});

test.afterAll(async () => {
  // Paid: terminate it. Still PENDING (the test stopped early): cancel it before activation (docs/04).
  const now = line && (await (await staff.get(`/api/v1/subscriptions/${line.id}`)).json());
  const close = now?.state === 'ACTIVE' ? 'terminate' : now?.state === 'PENDING' ? 'cancel' : null;
  if (close) {
    const closed = await staff.post(`/api/v1/subscriptions/${line!.id}/${close}`, {
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      data: { reason: 'End-to-end test clean-up' },
    });
    expect(closed.ok(), await closed.text()).toBeTruthy();
  }
  await staff?.dispose();
});

test('P6_payNowPushesToThePhoneAndTheLineIsRestoredWithinSeconds', async ({ page }) => {
  const opened = line!;
  await portalSignIn(page);
  await page.getByRole('link', { name: 'Pay', exact: true }).click();

  const card = page.locator('section.line', { hasText: opened.pppoeUsername });
  await expect(card.getByText('Not connected yet')).toBeVisible();
  const pay = card.getByRole('button', { name: /^Pay KES/ });
  const stk = page.waitForRequest(
    (r) => r.url().endsWith('/api/v1/portal/stk') && r.method() === 'POST',
  );
  await pay.click();
  expect((await stk).headers()['idempotency-key']).toBeTruthy();

  await expect(page.getByText('Check your phone.')).toBeVisible();
  // The fake answers the prompt after a moment; then renewal, then the restore check (docs/06).
  await expect(page.getByText('Internet restored.')).toBeVisible({ timeout: 45_000 });

  const now = (await (await staff.get(`/api/v1/subscriptions/${opened.id}`)).json()) as Line;
  expect(now.state).toBe('ACTIVE');
  expect(now.accessProfile).toBe('FULL');

  // One payment, credited once (P1, P4): it is in the customer's history with its receipt.
  await page.getByRole('button', { name: 'Done' }).click();
  await page.getByRole('link', { name: 'History', exact: true }).click();
  await expect(page.getByText('M-Pesa prompt').first()).toBeVisible();
});
