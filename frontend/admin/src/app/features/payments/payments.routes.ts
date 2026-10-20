import { Routes } from '@angular/router';

export const PAYMENTS_ROUTES: Routes = [
  {
    path: '',
    title: 'Payments',
    loadComponent: () => import('./payments-page.component').then((m) => m.PaymentsPageComponent),
  },
  {
    path: 'suspense',
    title: 'Suspense',
    loadComponent: () => import('./suspense-page.component').then((m) => m.SuspensePageComponent),
  },
  {
    path: 'reconciliation',
    title: 'Reconciliation',
    loadComponent: () =>
      import('./reconciliation-page.component').then((m) => m.ReconciliationPageComponent),
  },
];
