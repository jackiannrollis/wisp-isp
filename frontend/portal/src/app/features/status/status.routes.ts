import { Routes } from '@angular/router';

export const STATUS_ROUTES: Routes = [
  {
    path: '',
    title: 'My internet',
    loadComponent: () => import('./status-page.component').then((m) => m.StatusPageComponent),
  },
];
