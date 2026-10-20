import { Routes } from '@angular/router';

export const SETTINGS_ROUTES: Routes = [
  {
    path: '',
    title: 'Settings',
    data: { heading: 'Settings' },
    loadComponent: () =>
      import('../../shared/components/placeholder-page.component').then(
        (m) => m.PlaceholderPageComponent,
      ),
  },
];
