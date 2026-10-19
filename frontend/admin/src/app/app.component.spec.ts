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

  it('sends a visitor without a session to the sign-in page, remembering where they were going', async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    });
    const harness = RouterTestingHarness.create('/customers');
    const refresh = await vi.waitFor(() =>
      TestBed.inject(HttpTestingController).expectOne('/api/v1/auth/session/refresh'),
    );
    refresh.flush({ detail: 'no session' }, { status: 401, statusText: 'Unauthorized' });
    await harness;
    await vi.waitFor(() => expect(TestBed.inject(Router).url).toBe('/login?next=%2Fcustomers'));
  });
});
