import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { handoffCode } from '../../features/auth/impersonate-page.component';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';
import { formatCountdown } from './impersonation';

describe('impersonation (ADR 0017)', () => {
  it('counts down in mm:ss and stops at zero', () => {
    expect(formatCountdown(30 * 60_000)).toBe('30:00');
    expect(formatCountdown(65_400)).toBe('01:06');
    expect(formatCountdown(999)).toBe('00:01');
    expect(formatCountdown(-5_000)).toBe('00:00');
  });

  it('reads the hand-off code from the fragment only', () => {
    const code = 'Ab3_dEf-ghIjklMnOpQrStUvWxYz0123456789abcdE';
    expect(handoffCode('#code=' + code)).toBe(code);
    expect(handoffCode('#other=1&code=' + code)).toBe(code);
    expect(handoffCode('')).toBeNull();
    expect(handoffCode('#code=short')).toBeNull();
    expect(handoffCode('#code=' + code + '<script>')).toBeNull();
  });

  describe('the impersonated session', () => {
    let http: HttpClient;
    let httpMock: HttpTestingController;
    let auth: AuthService;

    beforeEach(() => {
      TestBed.configureTestingModule({
        providers: [
          provideHttpClient(withInterceptors([authInterceptor])),
          provideHttpClientTesting(),
          provideRouter([{ path: 'impersonation-ended', children: [] }]),
        ],
      });
      http = TestBed.inject(HttpClient);
      httpMock = TestBed.inject(HttpTestingController);
      auth = TestBed.inject(AuthService);
    });

    afterEach(() => httpMock.verify());

    async function start(): Promise<void> {
      const started = auth.startImpersonation('handoff-code-fake-0123456789');
      const exchange = httpMock.expectOne('/api/v1/auth/impersonation');
      expect(exchange.request.body).toEqual({ code: 'handoff-code-fake-0123456789' });
      exchange.flush({
        accessToken: 'imp.token.fake',
        expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
        impersonationId: 'imp-1',
        staffName: 'Wanjiru Owner',
        platformAdminName: 'Corebalt Support',
      });
      await Promise.resolve();
      httpMock.expectOne('/api/v1/me').flush({ displayName: 'Wanjiru Owner' });
      await started;
    }

    it('holds the token in memory and shows who acts as whom', async () => {
      await start();
      expect(auth.token()).toBe('imp.token.fake');
      expect(auth.impersonation()?.staffName).toBe('Wanjiru Owner');
      expect(auth.impersonating()).toBe(true);
    });

    it('never asks for a refresh: a refused token ends the session', async () => {
      await start();
      let failed = false;
      http.get('/api/v1/customers').subscribe({ error: () => (failed = true) });
      httpMock
        .expectOne('/api/v1/customers')
        .flush(null, { status: 401, statusText: 'Unauthorized' });
      await Promise.resolve();
      httpMock.expectNone('/api/v1/auth/session/refresh');
      expect(failed).toBe(true);
      expect(auth.token()).toBeNull();
      expect(auth.impersonationEnded()).toBe(true);
    });

    it('End tells the server and clears the session', async () => {
      await start();
      const ended = auth.endImpersonation();
      const req = httpMock.expectOne('/api/v1/impersonation/end');
      expect(req.request.headers.get('Authorization')).toBe('Bearer imp.token.fake');
      req.flush({});
      await ended;
      expect(auth.isAuthenticated()).toBe(false);
      expect(auth.impersonationEnded()).toBe(true);
    });
  });
});
