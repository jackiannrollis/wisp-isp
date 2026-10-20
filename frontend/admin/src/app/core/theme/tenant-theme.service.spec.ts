import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import {
  DEFAULT_BRANDING,
  MIN_CONTRAST,
  NEUTRAL,
  TenantThemeService,
  contrastRatio,
  themeScheme,
} from './tenant-theme.service';

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
    rootStyle().removeProperty('--mat-sys-primary');
  });

  afterEach(() => httpMock.verify());

  it('generates the M3 scheme from the tenant colour of /api/v1/branding', async () => {
    const loading = service.load();
    httpMock
      .expectOne('/api/v1/branding')
      .flush({ name: 'Skynet WISP', logoUrl: '/logo.png', primaryColor: '#ff6600' });
    await loading;

    const scheme = themeScheme('#ff6600');
    expect(rootStyle().getPropertyValue('--mat-sys-primary')).toBe(
      `light-dark(${scheme.primary.light}, ${scheme.primary.dark})`,
    );
    expect(rootStyle().getPropertyValue('--mat-sys-surface-container-high')).toContain(
      'light-dark(',
    );
    expect(service.branding()).toEqual({
      name: 'Skynet WISP',
      logoUrl: '/logo.png',
      primaryColor: '#ff6600',
    });
  });

  it('falls back to the default teal when the request fails', async () => {
    const loading = service.load();
    httpMock
      .expectOne('/api/v1/branding')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    await loading;

    expect(service.branding()).toEqual(DEFAULT_BRANDING);
    expect(rootStyle().getPropertyValue('--mat-sys-primary')).toContain(
      themeScheme('#0f766e').primary.light,
    );
  });

  it('ignores a colour that is not a hex value', () => {
    service.apply({ name: 'X', logoUrl: null, primaryColor: 'red; background: url(x)' });
    expect(service.branding().primaryColor).toBe(DEFAULT_BRANDING.primaryColor);
    expect(rootStyle().getPropertyValue('--mat-sys-primary')).not.toContain('url');
  });

  // The contrast check (ADR 0002 amendment): whatever colour a tenant picks, text on primary and
  // primary text on the surface stay readable, in light and dark.
  it.each([
    '#0f766e',
    '#ff6600',
    '#ffeb3b',
    '#00ff00',
    '#7fffd4',
    '#000000',
    '#ffffff',
    '#123',
    '#e91e63',
  ])('keeps %s legible in both modes', (brand) => {
    const scheme = themeScheme(brand);
    for (const mode of ['light', 'dark'] as const) {
      expect(contrastRatio(scheme.primary[mode], scheme.onPrimary[mode])).toBeGreaterThanOrEqual(
        MIN_CONTRAST,
      );
      expect(contrastRatio(scheme.primary[mode], scheme.surface[mode])).toBeGreaterThanOrEqual(
        MIN_CONTRAST,
      );
      expect(contrastRatio(scheme.onSurface[mode], scheme.surface[mode])).toBeGreaterThanOrEqual(
        MIN_CONTRAST,
      );
    }
  });

  it('keeps text readable on every fixed neutral surface, light and dark', () => {
    const surfaces = [
      'surface',
      'surfaceContainerLow',
      'surfaceContainer',
      'surfaceContainerHigh',
    ] as const;
    for (const mode of ['light', 'dark'] as const) {
      for (const surface of surfaces) {
        const background = NEUTRAL[surface]![mode];
        expect(contrastRatio(NEUTRAL.onSurface![mode], background)).toBeGreaterThanOrEqual(
          MIN_CONTRAST,
        );
        expect(contrastRatio(NEUTRAL.onSurfaceVariant![mode], background)).toBeGreaterThanOrEqual(
          MIN_CONTRAST,
        );
      }
    }
  });

  it('measures contrast like WCAG', () => {
    expect(contrastRatio('#000000', '#ffffff')).toBeCloseTo(21, 1);
    expect(contrastRatio('#167236', '#ffffff')).toBeGreaterThanOrEqual(MIN_CONTRAST);
    expect(contrastRatio('#a35600', '#ffffff')).toBeGreaterThanOrEqual(MIN_CONTRAST);
  });
});
