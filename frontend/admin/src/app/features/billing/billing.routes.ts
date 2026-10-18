import { Routes } from '@angular/router';

export const BILLING_ROUTES: Routes = [
  {
    path: '',
    title: 'Billing',
    loadComponent: () => import('./billing-page.component').then((m) => m.BillingPageComponent),
  },
];
