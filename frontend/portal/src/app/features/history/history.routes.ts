import { Routes } from '@angular/router';

export const HISTORY_ROUTES: Routes = [
  {
    path: '',
    title: 'History',
    loadComponent: () => import('./history-page.component').then((m) => m.HistoryPageComponent),
  },
];
