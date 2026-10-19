import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { AuthService } from '../auth/auth.service';
import { NAV_GROUPS, visibleNav } from './nav-items';
import { ShellComponent } from './shell.component';

/** An unsigned token carrying these permission codes (the UI only reads claims; the server verifies). */
function tokenWith(permissions: string[]): string {
  const payload = btoa(JSON.stringify({ sub: 'staff-1', permissions }))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
  return `e30.${payload}.sig`;
}

describe('visibleNav', () => {
  it('shows an Owner every v1 screen', () => {
    const all = new Set([
      'finance.read',
      'payments.assign',
      'payments.reconcile',
      'staff.manage',
      'audit.read',
      'settings.secrets',
    ]);
    expect(visibleNav(all).flatMap((g) => g.items).length).toBe(
      NAV_GROUPS.flatMap((g) => g.items).length,
    );
  });

  it('hides money and administration from a Technician, and empty groups with them', () => {
    const groups = visibleNav(new Set(['network.write', 'subscriptions.write']));
    expect(groups.map((g) => g.label)).toEqual(['Overview', 'Subscribers', 'Network']);
  });

  it('lets payments.assign reach the suspense queue without finance.read', () => {
    const finance = visibleNav(new Set(['payments.assign'])).find((g) => g.label === 'Finance');
    expect(finance?.items.map((i) => i.label)).toEqual(['Suspense']);
  });
});

describe('ShellComponent', () => {
  it('renders each visible item with its icon and label', async () => {
    TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(AuthService).setToken(tokenWith(['network.write']));
    const fixture = TestBed.createComponent(ShellComponent);
    await fixture.whenStable();

    const links = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('nav a'));
    expect(links.map((a) => a.textContent?.trim())).toEqual([
      'Dashboard',
      'Customers',
      'Lines',
      'Packages',
      'Routers',
      'Alerts',
    ]);
    expect(links.every((a) => a.querySelector('svg') !== null)).toBe(true);
  });
});
