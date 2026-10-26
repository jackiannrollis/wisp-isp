import { Routes } from '@angular/router';

export const CUSTOMERS_ROUTES: Routes = [
  {
    path: '',
    title: 'Customers',
    loadComponent: () => import('./customers-page.component').then((m) => m.CustomersPageComponent),
  },
  {
    path: 'import',
    title: 'Import customers',
    loadComponent: () =>
      import('./import-page.component').then((m) => m.CustomerImportPageComponent),
  },
  {
    path: ':id',
    title: 'Customer',
    loadComponent: () =>
      import('./customer-detail-page.component').then((m) => m.CustomerDetailPageComponent),
  },
];
