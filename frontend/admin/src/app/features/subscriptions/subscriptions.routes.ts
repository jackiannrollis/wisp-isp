import { Routes } from '@angular/router';

export const SUBSCRIPTIONS_ROUTES: Routes = [
  {
    path: '',
    title: 'Subscriptions',
    loadComponent: () =>
      import('./subscriptions-page.component').then((m) => m.SubscriptionsPageComponent),
  },
];
