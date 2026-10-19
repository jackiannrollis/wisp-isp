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
 * The M3 scheme for a brand colour. Fidelity keeps the brand's own chroma for the primary roles; when
 * that would put text on primary (or primary text on the surface) below 4.5:1, in light or dark, the
 * standard tonal-spot scheme of the same hue is used instead, whose tones guarantee the contrast.
 */
export function themeScheme(brandHex: string): ThemeScheme {
  const source = Hct.fromInt(argbFromHex(normalise(brandHex)));
  const fidelity = schemeOf(
    new SchemeFidelity(source, false, 0),
    new SchemeFidelity(source, true, 0),
  );
  return legible(fidelity)
    ? fidelity
    : schemeOf(new SchemeTonalSpot(source, false, 0), new SchemeTonalSpot(source, true, 0));
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
