import { DOCUMENT } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { Branding } from '../../shared/models';
import { API_BASE } from '../api/api.config';

export const DEFAULT_BRANDING: Branding = {
  name: 'Corebalt',
  logoUrl: null,
  primaryColor: '#0f766e',
};

const HEX_COLOR = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i;

/**
 * Loads the tenant's branding at startup and exposes it as CSS variables on <html>
 * (`--brand-primary`, `--brand-on-primary`). Falls back to the defaults on any error.
 */
@Injectable({ providedIn: 'root' })
export class TenantThemeService {
  private readonly http = inject(HttpClient);
  private readonly document = inject(DOCUMENT);

  private readonly brandingSignal = signal<Branding>(DEFAULT_BRANDING);
  readonly branding = this.brandingSignal.asReadonly();

  async load(): Promise<void> {
    try {
      const branding = await firstValueFrom(this.http.get<Branding>(`${API_BASE}/branding`));
      this.apply(branding);
    } catch {
      this.apply(DEFAULT_BRANDING);
    }
  }

  apply(branding: Partial<Branding> | null): void {
    const primaryColor =
      branding?.primaryColor && HEX_COLOR.test(branding.primaryColor)
        ? branding.primaryColor
        : DEFAULT_BRANDING.primaryColor;
    const root = this.document.documentElement;
    root.style.setProperty('--brand-primary', primaryColor);
    root.style.setProperty('--brand-on-primary', contrastColor(primaryColor));
    this.brandingSignal.set({
      name: branding?.name || DEFAULT_BRANDING.name,
      logoUrl: branding?.logoUrl ?? null,
      primaryColor,
    });
  }
}

/** Black or white text, whichever reads better on the given hex background. */
export function contrastColor(hex: string): string {
  let value = hex.slice(1);
  if (value.length === 3) {
    value = [...value].map((c) => c + c).join('');
  }
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(value.slice(i, i + 2), 16) / 255);
  const luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
  return luminance > 0.6 ? '#000000' : '#ffffff';
}
