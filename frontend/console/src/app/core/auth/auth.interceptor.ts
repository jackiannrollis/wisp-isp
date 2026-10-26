import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, from, switchMap, throwError } from 'rxjs';

import { AuthService } from './auth.service';

const SIGN_IN = '/api/platform/auth/';

/**
 * Attaches `Authorization: Bearer <token>` to same-origin `/api/` requests, so the token never leaks
 * to third parties. On a 401 it refreshes the access token from the cookie once and retries; if that
 * fails the admin goes back to the sign-in page.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (!req.url.startsWith('/api/') || req.url.startsWith(SIGN_IN)) {
    return next(req);
  }
  return next(withToken(req, auth.token())).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse) || error.status !== 401) {
        return throwError(() => error);
      }
      return from(auth.restore()).pipe(
        switchMap((restored) => {
          if (!restored) {
            void router.navigate(['/login'], { queryParams: { next: router.url } });
            return throwError(() => error);
          }
          return next(withToken(req, auth.token()));
        }),
      );
    }),
  );
};

function withToken(req: HttpRequest<unknown>, token: string | null): HttpRequest<unknown> {
  return token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;
}
