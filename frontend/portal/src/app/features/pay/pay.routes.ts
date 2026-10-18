import { Routes } from '@angular/router';

export const PAY_ROUTES: Routes = [
  {
    path: '',
    title: 'Pay',
    loadComponent: () => import('./pay-page.component').then((m) => m.PayPageComponent),
  },
];
