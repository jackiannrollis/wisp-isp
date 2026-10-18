import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { AuthService } from '../../core/auth/auth.service';
import { HasPermissionDirective } from './has-permission.directive';

@Component({
  imports: [HasPermissionDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<p id="guarded" *hasPermission="'customers.read'">secret</p>`,
})
class HostComponent {}

function fakeJwt(payload: object): string {
  const encode = (value: object) => btoa(JSON.stringify(value)).replace(/=+$/, '');
  return `${encode({ alg: 'none' })}.${encode(payload)}.signature`;
}

describe('HasPermissionDirective', () => {
  let auth: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    auth = TestBed.inject(AuthService);
  });

  async function render(): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(HostComponent);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('hides the element when the permission is missing', async () => {
    auth.setToken(fakeJwt({ permissions: ['payments.read'] }));
    const el = await render();
    expect(el.querySelector('#guarded')).toBeNull();
  });

  it('shows the element when the token carries the permission', async () => {
    auth.setToken(fakeJwt({ permissions: ['customers.read'] }));
    const el = await render();
    expect(el.querySelector('#guarded')?.textContent).toBe('secret');
  });

  it('reacts when the permissions change', async () => {
    const fixture = TestBed.createComponent(HostComponent);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('#guarded')).toBeNull();

    auth.setToken(fakeJwt({ permissions: ['customers.read'] }));
    await fixture.whenStable();
    expect(el.querySelector('#guarded')).not.toBeNull();

    auth.clear();
    await fixture.whenStable();
    expect(el.querySelector('#guarded')).toBeNull();
  });
});
