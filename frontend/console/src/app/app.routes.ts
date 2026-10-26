import { Routes } from '@angular/router';

import { authGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  {
    path: 'login',
    title: 'Sign in · Corebalt console',
    loadComponent: () =>
      import('./features/login/login-page.component').then((m) => m.LoginPageComponent),
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./core/layout/shell.component').then((m) => m.ShellComponent),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'tenants' },
      {
        path: 'tenants',
        title: 'Tenants · Corebalt console',
        loadComponent: () =>
          import('./features/tenants/tenants-page.component').then((m) => m.TenantsPageComponent),
      },
      {
        path: 'tenants/:id',
        title: 'Tenant · Corebalt console',
        loadComponent: () =>
          import('./features/tenants/tenant-page.component').then((m) => m.TenantPageComponent),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
