import { Routes } from '@angular/router';

import { authGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  {
    path: 'login',
    title: 'Sign in',
    loadComponent: () =>
      import('./features/auth/login-page.component').then((m) => m.LoginPageComponent),
  },
  // ADR 0017: the console's hand-off into an impersonation, and where it ends; outside the guard.
  {
    path: 'impersonate',
    title: 'Impersonation',
    loadComponent: () =>
      import('./features/auth/impersonate-page.component').then((m) => m.ImpersonatePageComponent),
  },
  {
    path: 'impersonation-ended',
    title: 'Impersonation ended',
    loadComponent: () =>
      import('./features/auth/impersonate-page.component').then(
        (m) => m.ImpersonationEndedPageComponent,
      ),
  },
  {
    path: '',
    loadComponent: () => import('./core/layout/shell.component').then((m) => m.ShellComponent),
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
      {
        path: 'dashboard',
        loadChildren: () =>
          import('./features/dashboard/dashboard.routes').then((m) => m.DASHBOARD_ROUTES),
      },
      {
        path: 'customers',
        loadChildren: () =>
          import('./features/customers/customers.routes').then((m) => m.CUSTOMERS_ROUTES),
      },
      {
        path: 'subscriptions',
        loadChildren: () =>
          import('./features/subscriptions/subscriptions.routes').then(
            (m) => m.SUBSCRIPTIONS_ROUTES,
          ),
      },
      {
        path: 'packages',
        loadChildren: () =>
          import('./features/packages/packages.routes').then((m) => m.PACKAGES_ROUTES),
      },
      {
        path: 'payments',
        loadChildren: () =>
          import('./features/payments/payments.routes').then((m) => m.PAYMENTS_ROUTES),
      },
      {
        path: 'network',
        loadChildren: () =>
          import('./features/network/network.routes').then((m) => m.NETWORK_ROUTES),
      },
      {
        path: 'sessions',
        title: 'Sessions',
        loadComponent: () =>
          import('./features/network/sessions-page.component').then((m) => m.SessionsPageComponent),
      },
      {
        path: 'alerts',
        loadChildren: () => import('./features/alerts/alerts.routes').then((m) => m.ALERTS_ROUTES),
      },
      {
        path: 'staff',
        loadChildren: () => import('./features/staff/staff.routes').then((m) => m.STAFF_ROUTES),
      },
      {
        path: 'audit-log',
        loadChildren: () => import('./features/audit/audit.routes').then((m) => m.AUDIT_ROUTES),
      },
      {
        path: 'settings',
        loadChildren: () =>
          import('./features/settings/settings.routes').then((m) => m.SETTINGS_ROUTES),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
