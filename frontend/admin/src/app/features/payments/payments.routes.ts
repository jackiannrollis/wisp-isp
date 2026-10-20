import { Routes } from '@angular/router';

const placeholder = () =>
  import('../../shared/components/placeholder-page.component').then(
    (m) => m.PlaceholderPageComponent,
  );

export const PAYMENTS_ROUTES: Routes = [
  {
    path: '',
    title: 'Payments',
    data: { heading: 'Payments' },
    loadComponent: placeholder,
  },
  {
    path: 'suspense',
    title: 'Suspense',
    data: { heading: 'Suspense' },
    loadComponent: placeholder,
  },
  {
    path: 'reconciliation',
    title: 'Reconciliation',
    data: { heading: 'Reconciliation' },
    loadComponent: placeholder,
  },
];
