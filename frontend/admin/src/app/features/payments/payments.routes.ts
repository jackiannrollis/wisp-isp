import { Routes } from '@angular/router';

export const PAYMENTS_ROUTES: Routes = [
  {
    path: '',
    title: 'Payments',
    loadComponent: () => import('./payments-page.component').then((m) => m.PaymentsPageComponent),
  },
];
