import { Routes } from '@angular/router';

import { authGuard } from './core/auth/auth.guard';
import { ShellComponent } from './core/layout/shell.component';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () =>
      import('./features/auth/login-page.component').then((m) => m.LoginPageComponent),
  },
  {
    path: '',
    component: ShellComponent,
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'status' },
      {
        path: 'status',
        loadChildren: () => import('./features/status/status.routes').then((m) => m.STATUS_ROUTES),
      },
      {
        path: 'pay',
        loadChildren: () => import('./features/pay/pay.routes').then((m) => m.PAY_ROUTES),
      },
      {
        path: 'history',
        loadChildren: () =>
          import('./features/history/history.routes').then((m) => m.HISTORY_ROUTES),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
