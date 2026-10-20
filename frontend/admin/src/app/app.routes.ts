import { Routes } from '@angular/router';

import { authGuard } from './core/auth/auth.guard';

const placeholder = () =>
  import('./shared/components/placeholder-page.component').then((m) => m.PlaceholderPageComponent);

export const routes: Routes = [
  {
    path: 'login',
    title: 'Sign in',
    loadComponent: () =>
      import('./features/auth/login-page.component').then((m) => m.LoginPageComponent),
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
        path: 'alerts',
        loadChildren: () => import('./features/alerts/alerts.routes').then((m) => m.ALERTS_ROUTES),
      },
      {
        path: 'staff',
        loadChildren: () => import('./features/staff/staff.routes').then((m) => m.STAFF_ROUTES),
      },
      {
        path: 'audit-log',
        title: 'Audit log',
        data: { heading: 'Audit log' },
        loadComponent: placeholder,
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
