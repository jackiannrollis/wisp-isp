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
import { DEFAULT_BRANDING, TenantThemeService } from './core/theme/tenant-theme.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(withInterceptors([authInterceptor])),
    provideRouter(routes, withComponentInputBinding()),
    provideTablerIconConfig({ stroke: 1.75, size: 20 }),
    // The default scheme before the first paint; the provider's own branding loads after sign-in
    // (authGuard), since /portal/branding needs a customer token.
    provideAppInitializer(() => inject(TenantThemeService).apply(DEFAULT_BRANDING)),
  ],
};
