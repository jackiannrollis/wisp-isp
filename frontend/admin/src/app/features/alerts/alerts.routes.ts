import { Routes } from '@angular/router';

export const ALERTS_ROUTES: Routes = [
  {
    path: '',
    title: 'Alerts',
    loadComponent: () => import('./alerts-page.component').then((m) => m.AlertsPageComponent),
  },
];
