import { Routes } from '@angular/router';

export const PACKAGES_ROUTES: Routes = [
  {
    path: '',
    title: 'Packages',
    data: { heading: 'Packages' },
    loadComponent: () =>
      import('../../shared/components/placeholder-page.component').then(
        (m) => m.PlaceholderPageComponent,
      ),
  },
];
