import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideTablerIconConfig } from '@tabler/icons-angular';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth/auth.interceptor';
import { ThemeModeService } from './core/theme/theme-mode.service';
import { DEFAULT_BRANDING, TenantThemeService } from './core/theme/tenant-theme.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(withInterceptors([authInterceptor])),
    provideRouter(routes, withComponentInputBinding()),
    // ADR 0002 amendment: one stroke width for every icon; sizes are set per place of use.
    provideTablerIconConfig({ stroke: 1.75, size: 20 }),
    // The default scheme and the saved light/dark choice before the first paint; the tenant's own
    // branding loads after sign-in (authGuard).
    provideAppInitializer(() => {
      inject(ThemeModeService);
      inject(TenantThemeService).apply(DEFAULT_BRANDING);
    }),
  ],
};
