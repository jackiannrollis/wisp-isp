import { Routes } from '@angular/router';

export const PACKAGES_ROUTES: Routes = [
  {
    path: '',
    title: 'Packages',
    loadComponent: () => import('./packages-page.component').then((m) => m.PackagesPageComponent),
  },
];
