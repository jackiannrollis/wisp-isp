import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AuthService } from './auth.service';

/**
 * Lets a signed-in platform admin through; otherwise tries the refresh cookie once (a reload) and
 * sends anyone without a session to /login. Guards only shape the UI; the server is the authority.
 */
export const authGuard: CanActivateFn = async (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (auth.isAuthenticated() || (await auth.restore())) {
    return true;
  }
  return router.createUrlTree(['/login'], { queryParams: { next: state.url } });
};
