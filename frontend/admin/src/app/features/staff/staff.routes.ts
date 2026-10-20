import { Routes } from '@angular/router';

export const STAFF_ROUTES: Routes = [
  {
    path: '',
    title: 'Staff and roles',
    loadComponent: () => import('./staff-page.component').then((m) => m.StaffPageComponent),
  },
];
