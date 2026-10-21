import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { LoginPageComponent } from './login-page.component';

describe('LoginPageComponent', () => {
  let fixture: ComponentFixture<LoginPageComponent>;
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;

  const page = () => fixture.nativeElement as HTMLElement;
  const type = (label: string, value: string) => {
    const field = Array.from(page().querySelectorAll('mat-form-field')).find((f) =>
      f.textContent?.includes(label),
    );
    const input = field?.querySelector('input') as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  };
  const submit = async () => {
    page().querySelector('form')?.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  };

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [LoginPageComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(LoginPageComponent);
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  it('T2_signsInWithTheAccountNumberAndPinAndTakesTheProviderFromTheNumber', async () => {
    type('Account number', 'cb000001');
    type('PIN', '4071');
    await submit();
    const login = http.expectOne('/api/v1/portal-auth/login');
    expect(login.request.body).toEqual({ tenant: 'CB', account: 'CB000001', pin: '4071' });
    login.flush({ status: 'SIGNED_IN', accessToken: 'a.b.c' });
    (await vi.waitFor(() => http.expectOne('/api/v1/portal/customer'))).flush({
      number: 'CB000001',
    });
    await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith('/status'));
  });

  it('shows the problem the server wrote, such as a lockout', async () => {
    type('Account number', 'CB000001');
    type('PIN', '1111');
    await submit();
    http
      .expectOne('/api/v1/portal-auth/login')
      .flush(
        { detail: 'Too many attempts. Try again in 15 minutes.' },
        { status: 401, statusText: 'Unauthorized' },
      );
    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(page().querySelector('[role=alert]')?.textContent).toContain('Too many attempts');
    });
  });

  it('ADR0010_setsAPinWithAnSmsCodeForOneOfTheAccountsOnThePhone', async () => {
    (
      Array.from(page().querySelectorAll('button')).find((b) =>
        b.textContent?.includes('Get a code'),
      ) as HTMLButtonElement
    ).click();
    await fixture.whenStable();
    type('Provider code', 'CB');
    type('Phone number', '0712345678');
    await submit();
    http.expectOne('/api/v1/portal-auth/code').flush(null, { status: 202, statusText: 'Accepted' });
    // The answer never says whether the phone is a customer's.
    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(page().textContent).toContain('If this number is registered');
    });

    type('Code', '123456');
    await submit();
    const verify = http.expectOne('/api/v1/portal-auth/code/verify');
    expect(verify.request.body).toEqual({ tenant: 'CB', phone: '0712345678', code: '123456' });
    verify.flush({
      status: 'CHOOSE_ACCOUNT',
      accounts: [
        { number: 'CB000001', name: 'Mary Wairimu', challenge: 'ch-1' },
        { number: 'CB000002', name: 'Mary Wairimu', challenge: 'ch-2' },
      ],
    });
    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(page().querySelectorAll('.accounts button')).toHaveLength(2);
    });
    (
      Array.from(page().querySelectorAll('.accounts button')).find((b) =>
        b.textContent?.includes('CB000002'),
      ) as HTMLButtonElement
    ).click();
    await fixture.whenStable();
    expect(page().textContent).toContain('For account CB000002');

    type('New PIN', '2580');
    type('Repeat the PIN', '2581');
    await submit();
    expect(page().querySelector('[role=alert]')?.textContent).toContain('not the same');

    type('Repeat the PIN', '2580');
    await submit();
    const pin = http.expectOne('/api/v1/portal-auth/pin');
    expect(pin.request.body).toEqual({ challenge: 'ch-2', pin: '2580' });
    pin.flush({ status: 'SIGNED_IN', accessToken: 'a.b.c' });
    (await vi.waitFor(() => http.expectOne('/api/v1/portal/customer'))).flush({
      number: 'CB000002',
    });
    await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith('/status'));
  });
});
