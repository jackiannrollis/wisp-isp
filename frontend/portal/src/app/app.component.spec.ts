import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';

import { AppComponent } from './app.component';
import { routes } from './app.routes';

describe('AppComponent', () => {
  it('should create the app', () => {
    TestBed.configureTestingModule({ imports: [AppComponent], providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(AppComponent);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('ADR0010_sendsACustomerWithoutASessionToSignIn', async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    });
    const harness = RouterTestingHarness.create('/');
    const httpMock = TestBed.inject(HttpTestingController);
    (await vi.waitFor(() => httpMock.expectOne('/api/v1/portal-auth/session/refresh'))).flush(
      null,
      { status: 401, statusText: 'Unauthorized' },
    );
    const page = await harness;
    await vi.waitFor(() => expect(TestBed.inject(Router).url).toBe('/login?next=%2Fstatus'));
    page.detectChanges();
    expect(page.routeNativeElement?.textContent).toContain('Account number');
  });
});
