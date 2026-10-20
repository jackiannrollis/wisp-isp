import { Routes } from '@angular/router';

export const NETWORK_ROUTES: Routes = [
  {
    path: '',
    title: 'Routers',
    loadComponent: () => import('./routers-page.component').then((m) => m.RoutersPageComponent),
  },
  {
    path: ':id',
    title: 'Router',
    loadComponent: () =>
      import('./router-detail-page.component').then((m) => m.RouterDetailPageComponent),
  },
];
