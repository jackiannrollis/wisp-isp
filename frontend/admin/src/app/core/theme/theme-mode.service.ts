import { DOCUMENT } from '@angular/common';
import { Injectable, inject, signal } from '@angular/core';

export type ThemeMode = 'system' | 'light' | 'dark';

const KEY = 'corebalt.theme-mode';

/**
 * Light, dark or the system's choice (ADR 0002 amendment). The choice is a per-browser convenience
 * kept in localStorage; when storage is unavailable the app simply follows the system.
 */
@Injectable({ providedIn: 'root' })
export class ThemeModeService {
  private readonly document = inject(DOCUMENT);
  private readonly modeSignal = signal<ThemeMode>(read());
  readonly mode = this.modeSignal.asReadonly();

  constructor() {
    this.render(this.modeSignal());
  }

  set(mode: ThemeMode): void {
    this.modeSignal.set(mode);
    this.render(mode);
    try {
      localStorage.setItem(KEY, mode);
    } catch {
      // Private window or blocked storage: the choice lasts for this page only.
    }
  }

  private render(mode: ThemeMode): void {
    const root = this.document.documentElement;
    if (mode === 'system') {
      root.removeAttribute('data-theme');
    } else {
      root.setAttribute('data-theme', mode);
    }
  }
}

function read(): ThemeMode {
  try {
    const stored = localStorage.getItem(KEY);
    return stored === 'light' || stored === 'dark' ? stored : 'system';
  } catch {
    return 'system';
  }
}
