import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
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
    http.get('/api/v1/customers').subscribe();
    const req = httpMock.expectOne('/api/v1/customers');
    expect(req.request.headers.get('Authorization')).toBe('Bearer abc.def.ghi');
    req.flush({});
  });

  it('sends no header when there is no token', () => {
    http.get('/api/v1/customers').subscribe();
    const req = httpMock.expectOne('/api/v1/customers');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });

  it('refreshes the token once on a 401 and retries with the new one', async () => {
    auth.setToken('old.token.x');
    let body: unknown;
    http.get('/api/v1/customers').subscribe((b) => (body = b));
    httpMock
      .expectOne('/api/v1/customers')
      .flush(null, { status: 401, statusText: 'Unauthorized' });
    const refresh = httpMock.expectOne('/api/v1/auth/session/refresh');
    expect(refresh.request.headers.has('Authorization')).toBe(false);
    refresh.flush({ status: 'SIGNED_IN', accessToken: 'new.token.y' });
    (await vi.waitFor(() => httpMock.expectOne('/api/v1/me'))).flush({ displayName: 'Lab admin' });
    const retried = await vi.waitFor(() => httpMock.expectOne('/api/v1/customers'));
    expect(retried.request.headers.get('Authorization')).toBe('Bearer new.token.y');
    retried.flush({ items: [] });
    expect(body).toEqual({ items: [] });
  });

  it('never sends the token to other origins', () => {
    auth.setToken('abc.def.ghi');
    http.get('https://example.com/data').subscribe();
    const req = httpMock.expectOne('https://example.com/data');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });
});
