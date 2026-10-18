import { Routes } from '@angular/router';

export const CUSTOMERS_ROUTES: Routes = [
  {
    path: '',
    title: 'Customers',
    loadComponent: () => import('./customers-page.component').then((m) => m.CustomersPageComponent),
  },
];
