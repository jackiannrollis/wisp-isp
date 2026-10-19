import { Routes } from '@angular/router';

export const NETWORK_ROUTES: Routes = [
  {
    path: '',
    title: 'Routers',
    loadComponent: () => import('./network-page.component').then((m) => m.NetworkPageComponent),
  },
];
