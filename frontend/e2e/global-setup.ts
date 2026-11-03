import { expect } from '@playwright/test';

import { LAB, staffApi } from './support';

export default async function setup(): Promise<void> {
  const response = await fetch(`${LAB.fakeDaraja}/health`);
  expect(response.ok, 'Start the isolated stack with infra/e2e/up first').toBeTruthy();
  expect((await response.json()).testOnly, 'Browser tests require fake providers').toBe(true);
  const api = await staffApi();
  await api.dispose();
}
