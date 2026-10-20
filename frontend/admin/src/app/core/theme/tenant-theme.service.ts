import { DOCUMENT } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import {
  DynamicScheme,
  Hct,
  SchemeFidelity,
  SchemeTonalSpot,
  argbFromHex,
  hexFromArgb,
} from '@material/material-color-utilities';
import { firstValueFrom } from 'rxjs';

import { Branding } from '../../shared/models';
import { API_BASE } from '../api/api.config';

export const DEFAULT_BRANDING: Branding = {
  name: 'Corebalt',
  logoUrl: null,
  primaryColor: '#0f766e',
};

const HEX_COLOR = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i;

/** WCAG AA for normal text (ADR 0002 amendment: the contrast check). */
export const MIN_CONTRAST = 4.5;

/** Every M3 colour role Angular Material reads, as `--mat-sys-<name>`. */
const ROLES = [
  'primary',
  'onPrimary',
  'primaryContainer',
  'onPrimaryContainer',
  'primaryFixed',
  'primaryFixedDim',
  'onPrimaryFixed',
  'onPrimaryFixedVariant',
  'inversePrimary',
  'secondary',
  'onSecondary',
  'secondaryContainer',
  'onSecondaryContainer',
  'secondaryFixed',
  'secondaryFixedDim',
  'onSecondaryFixed',
  'onSecondaryFixedVariant',
  'tertiary',
  'onTertiary',
  'tertiaryContainer',
  'onTertiaryContainer',
  'tertiaryFixed',
  'tertiaryFixedDim',
  'onTertiaryFixed',
  'onTertiaryFixedVariant',
  'error',
  'onError',
  'errorContainer',
  'onErrorContainer',
  'background',
  'onBackground',
  'surface',
  'onSurface',
  'surfaceVariant',
  'onSurfaceVariant',
  'surfaceDim',
  'surfaceBright',
  'surfaceContainerLowest',
  'surfaceContainerLow',
  'surfaceContainer',
  'surfaceContainerHigh',
  'surfaceContainerHighest',
  'inverseSurface',
  'inverseOnSurface',
  'outline',
  'outlineVariant',
  'shadow',
  'scrim',
  'surfaceTint',
] as const;

type Role = (typeof ROLES)[number];

/** Light and dark hex values of every role. */
export type ThemeScheme = Record<Role, { light: string; dark: string }>;

/**
 * Tenant branding at startup (after sign-in, since /branding is a staff endpoint). The brand colour
 * seeds a full Material 3 scheme, light and dark, written to <html> as `--mat-sys-*` light-dark()
 * pairs. Secondary and tertiary are derived; status colours stay fixed (styles.scss).
 */
@Injectable({ providedIn: 'root' })
export class TenantThemeService {
  private readonly http = inject(HttpClient);
  private readonly document = inject(DOCUMENT);

  private readonly brandingSignal = signal<Branding>(DEFAULT_BRANDING);
  readonly branding = this.brandingSignal.asReadonly();

  private loaded: Promise<void> | null = null;

  /** Loads the branding once per session (the guard calls it after sign-in). */
  loadOnce(): Promise<void> {
    this.loaded ??= this.load();
    return this.loaded;
  }

  /** After sign-out, the next session loads its own tenant's branding. */
  reset(): void {
    this.loaded = null;
    this.apply(DEFAULT_BRANDING);
  }

  async load(): Promise<void> {
    try {
      this.apply(await firstValueFrom(this.http.get<Branding>(`${API_BASE}/branding`)));
    } catch {
      this.apply(DEFAULT_BRANDING);
    }
  }

  apply(branding: Partial<Branding> | null): void {
    const primaryColor =
      branding?.primaryColor && HEX_COLOR.test(branding.primaryColor)
        ? branding.primaryColor
        : DEFAULT_BRANDING.primaryColor;
    const scheme = themeScheme(primaryColor);
    const style = this.document.documentElement.style;
    for (const role of ROLES) {
      style.setProperty(
        `--mat-sys-${kebab(role)}`,
        `light-dark(${scheme[role].light}, ${scheme[role].dark})`,
      );
    }
    this.brandingSignal.set({
      name: branding?.name || DEFAULT_BRANDING.name,
      logoUrl: branding?.logoUrl ?? null,
      primaryColor,
    });
  }
}

/**
 * The M3 scheme for a brand colour, on the fixed neutral surfaces. Fidelity keeps the brand's own chroma for the primary roles; when
 * that would put text on primary (or primary text on the surface) below 4.5:1, in light or dark, the
 * standard tonal-spot scheme of the same hue is used instead, whose tones guarantee the contrast.
 */
export function themeScheme(brandHex: string): ThemeScheme {
  const source = Hct.fromInt(argbFromHex(normalise(brandHex)));
  const fidelity = withNeutralSurfaces(
    schemeOf(new SchemeFidelity(source, false, 0), new SchemeFidelity(source, true, 0)),
  );
  return legible(fidelity)
    ? fidelity
    : withNeutralSurfaces(
        schemeOf(new SchemeTonalSpot(source, false, 0), new SchemeTonalSpot(source, true, 0)),
      );
}

/**
 * The fixed neutral ramp (ADR 0002 amendment, refined): surfaces, text and borders are the same for
 * every tenant, a cool grey with no brand tint, so a tenant's colour reads as an accent only. Text on
 * every surface reaches 4.5:1 (tested in tenant-theme.service.spec).
 */
export const NEUTRAL: Partial<ThemeScheme> = {
  background: { light: '#f6f7f9', dark: '#0e1014' },
  surface: { light: '#ffffff', dark: '#15181d' },
  surfaceDim: { light: '#e3e6ea', dark: '#0e1014' },
  surfaceBright: { light: '#ffffff', dark: '#2a2f37' },
  surfaceContainerLowest: { light: '#ffffff', dark: '#0e1014' },
  surfaceContainerLow: { light: '#f6f7f9', dark: '#111318' },
  surfaceContainer: { light: '#f0f2f5', dark: '#1b1f25' },
  surfaceContainerHigh: { light: '#e9ecf0', dark: '#21262d' },
  surfaceContainerHighest: { light: '#e2e6eb', dark: '#282e36' },
  surfaceVariant: { light: '#e9ecf0', dark: '#21262d' },
  onBackground: { light: '#161a1f', dark: '#e6e8eb' },
  onSurface: { light: '#161a1f', dark: '#e6e8eb' },
  onSurfaceVariant: { light: '#545d69', dark: '#a6aeb8' },
  outline: { light: '#8a929d', dark: '#6c747f' },
  outlineVariant: { light: '#dfe3e8', dark: '#2c323a' },
  inverseSurface: { light: '#2a2f36', dark: '#e6e8eb' },
  inverseOnSurface: { light: '#f0f2f5', dark: '#2a2f36' },
};

export function withNeutralSurfaces(scheme: ThemeScheme): ThemeScheme {
  return { ...scheme, ...NEUTRAL } as ThemeScheme;
}

function legible(scheme: ThemeScheme): boolean {
  return (['light', 'dark'] as const).every(
    (mode) =>
      contrastRatio(scheme.primary[mode], scheme.onPrimary[mode]) >= MIN_CONTRAST &&
      contrastRatio(scheme.primary[mode], scheme.surface[mode]) >= MIN_CONTRAST &&
      contrastRatio(scheme.primaryContainer[mode], scheme.onPrimaryContainer[mode]) >= MIN_CONTRAST,
  );
}

function schemeOf(light: DynamicScheme, dark: DynamicScheme): ThemeScheme {
  const scheme = {} as ThemeScheme;
  for (const role of ROLES) {
    scheme[role] = { light: hexFromArgb(light[role]), dark: hexFromArgb(dark[role]) };
  }
  return scheme;
}

/** WCAG 2 contrast ratio of two hex colours. */
export function contrastRatio(a: string, b: string): number {
  const [la, lb] = [luminance(a), luminance(b)];
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

function luminance(hex: string): number {
  const value = normalise(hex).slice(1);
  const [r, g, b] = [0, 2, 4].map((i) => {
    const c = parseInt(value.slice(i, i + 2), 16) / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function normalise(hex: string): string {
  return hex.length === 4 ? '#' + [...hex.slice(1)].map((c) => c + c).join('') : hex;
}

function kebab(role: string): string {
  return role.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase());
}
