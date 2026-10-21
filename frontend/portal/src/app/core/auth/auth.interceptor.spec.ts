import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { authInterceptor } from './auth.interceptor';
import { AuthService, tenantOfAccount } from './auth.service';

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => httpMock.verify());

  it('adds the bearer token to API requests', () => {
    auth.setToken('abc.def.ghi');
    http.get('/api/v1/portal/lines').subscribe();
    const req = httpMock.expectOne('/api/v1/portal/lines');
    expect(req.request.headers.get('Authorization')).toBe('Bearer abc.def.ghi');
    req.flush({});
  });

  it('sends no token with the sign-in steps', () => {
    auth.setToken('abc.def.ghi');
    http.post('/api/v1/portal-auth/login', {}).subscribe();
    const req = httpMock.expectOne('/api/v1/portal-auth/login');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });

  it('ADR0010_refreshesOnceOnA401AndRetriesWithTheNewToken', async () => {
    auth.setToken('old.token.x');
    let body: unknown;
    http.get('/api/v1/portal/lines').subscribe((b) => (body = b));
    httpMock
      .expectOne('/api/v1/portal/lines')
      .flush(null, { status: 401, statusText: 'Unauthorized' });
    const refresh = httpMock.expectOne('/api/v1/portal-auth/session/refresh');
    expect(refresh.request.headers.has('Authorization')).toBe(false);
    refresh.flush({ status: 'SIGNED_IN', accessToken: 'new.token.y' });
    (await vi.waitFor(() => httpMock.expectOne('/api/v1/portal/customer'))).flush({
      number: 'CB000001',
    });
    const retried = await vi.waitFor(() => httpMock.expectOne('/api/v1/portal/lines'));
    expect(retried.request.headers.get('Authorization')).toBe('Bearer new.token.y');
    retried.flush({ lines: [] });
    expect(body).toEqual({ lines: [] });
    expect(auth.customer()?.number).toBe('CB000001');
  });

  it('sends the customer to sign in when the session cannot be refreshed', async () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    auth.setToken('old.token.x');
    http.get('/api/v1/portal/lines').subscribe({ error: () => undefined });
    httpMock
      .expectOne('/api/v1/portal/lines')
      .flush(null, { status: 401, statusText: 'Unauthorized' });
    httpMock
      .expectOne('/api/v1/portal-auth/session/refresh')
      .flush(null, { status: 401, statusText: 'Unauthorized' });
    await vi.waitFor(() => expect(navigate).toHaveBeenCalled());
    expect(navigate.mock.calls[0][0]).toEqual(['/login']);
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('never sends the token to other origins', () => {
    auth.setToken('abc.def.ghi');
    http.get('https://example.com/data').subscribe();
    const req = httpMock.expectOne('https://example.com/data');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });
});

describe('tenantOfAccount', () => {
  it('T2_readsTheProviderCodeFromTheAccountNumber', () => {
    expect(tenantOfAccount('CB000001')).toBe('CB');
    expect(tenantOfAccount(' cb001238-2 ')).toBe('CB');
    expect(tenantOfAccount('0712345678')).toBeNull();
    expect(tenantOfAccount('CB12')).toBeNull();
  });
});
