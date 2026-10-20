import { Routes } from '@angular/router';

export const AUDIT_ROUTES: Routes = [
  {
    path: '',
    title: 'Audit log',
    loadComponent: () => import('./audit-page.component').then((m) => m.AuditPageComponent),
  },
];
