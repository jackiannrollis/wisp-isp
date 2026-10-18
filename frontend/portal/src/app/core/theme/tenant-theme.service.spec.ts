import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { DEFAULT_BRANDING, TenantThemeService, contrastColor } from './tenant-theme.service';

describe('TenantThemeService', () => {
  let service: TenantThemeService;
  let httpMock: HttpTestingController;
  const rootStyle = () => document.documentElement.style;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(TenantThemeService);
    httpMock = TestBed.inject(HttpTestingController);
    rootStyle().removeProperty('--brand-primary');
    rootStyle().removeProperty('--brand-on-primary');
  });

  afterEach(() => httpMock.verify());

  it('applies the tenant colour from /api/v1/branding', async () => {
    const loading = service.load();
    httpMock
      .expectOne('/api/v1/branding')
      .flush({ name: 'Skynet WISP', logoUrl: '/logo.png', primaryColor: '#ff6600' });
    await loading;

    expect(rootStyle().getPropertyValue('--brand-primary')).toBe('#ff6600');
    expect(service.branding()).toEqual({
      name: 'Skynet WISP',
      logoUrl: '/logo.png',
      primaryColor: '#ff6600',
    });
  });

  it('falls back to the defaults when the request fails', async () => {
    const loading = service.load();
    httpMock
      .expectOne('/api/v1/branding')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    await loading;

    expect(rootStyle().getPropertyValue('--brand-primary')).toBe(DEFAULT_BRANDING.primaryColor);
    expect(service.branding()).toEqual(DEFAULT_BRANDING);
  });

  it('ignores a colour that is not a hex value', () => {
    service.apply({ name: 'X', logoUrl: null, primaryColor: 'red; background: url(x)' });
    expect(rootStyle().getPropertyValue('--brand-primary')).toBe(DEFAULT_BRANDING.primaryColor);
  });

  it('picks readable text colours', () => {
    expect(contrastColor('#ffffff')).toBe('#000000');
    expect(contrastColor('#000')).toBe('#ffffff');
  });
});
