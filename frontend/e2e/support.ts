import { APIRequestContext, Page, expect, request } from '@playwright/test';

/**
 * The lab fixtures of infra/lab/seed.sql: obvious fakes that exist only on a local or CI stack
 * (CLAUDE.md). Override them through the environment, never on a command line.
 */
export const LAB = {
  api: process.env['E2E_API_URL'] ?? 'http://localhost:8080',
  fakeDaraja: process.env['E2E_FAKE_DARAJA_URL'] ?? 'http://localhost:8095',
  tenant: process.env['E2E_TENANT'] ?? 'CB',
  adminEmail: process.env['E2E_ADMIN_EMAIL'] ?? 'lab-admin@corebalt.example.test',
  adminPassword: process.env['E2E_ADMIN_PASSWORD'] ?? 'lab-admin-password',
  customer: process.env['E2E_CUSTOMER'] ?? 'CB000001',
  customerPin: process.env['E2E_CUSTOMER_PIN'] ?? '4071',
};

/** Signs the lab admin in through the admin app's sign-in page. */
export async function adminSignIn(page: Page): Promise<void> {
  await page.goto('/');
  await expect(page).toHaveURL(/\/login/);
  await page.getByLabel('Organisation code').fill(LAB.tenant);
  await page.getByLabel('Email').fill(LAB.adminEmail);
  await page.getByLabel('Password').fill(LAB.adminPassword);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/dashboard/);
}

/** Signs the lab customer into the portal with account number and PIN. */
export async function portalSignIn(page: Page): Promise<void> {
  await page.goto('/');
  await expect(page).toHaveURL(/\/login/);
  await page.getByLabel('Account number').fill(LAB.customer);
  await page.getByLabel('PIN').fill(LAB.customerPin);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/status/);
}

/** The staff API as the lab admin, for test setup and clean-up only. */
export async function staffApi(): Promise<APIRequestContext> {
  const anonymous = await request.newContext({ baseURL: LAB.api });
  const login = await anonymous.post('/api/v1/auth/login', {
    data: { tenant: LAB.tenant, email: LAB.adminEmail, password: LAB.adminPassword },
  });
  expect(login.ok(), 'lab admin sign-in (load infra/lab/seed.sql first)').toBeTruthy();
  const { accessToken } = (await login.json()) as { accessToken: string };
  await anonymous.dispose();
  return request.newContext({
    baseURL: LAB.api,
    extraHTTPHeaders: { Authorization: `Bearer ${accessToken}` },
  });
}
