import { Routes } from '@angular/router';

export const SETTINGS_ROUTES: Routes = [
  {
    path: '',
    title: 'Settings',
    loadComponent: () => import('./settings-page.component').then((m) => m.SettingsPageComponent),
  },
];
