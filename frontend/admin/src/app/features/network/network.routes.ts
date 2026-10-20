import { Routes } from '@angular/router';

export const NETWORK_ROUTES: Routes = [
  {
    path: '',
    title: 'Routers',
    data: { heading: 'Routers' },
    loadComponent: () =>
      import('../../shared/components/placeholder-page.component').then(
        (m) => m.PlaceholderPageComponent,
      ),
  },
];
