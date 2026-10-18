import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';

import { AuthService } from './auth.service';

/**
 * STUB: attaches `Authorization: Bearer <token>` to API requests when a token is present.
 * Refresh and 401 handling land with real auth in step 8 of docs/09-roadmap.md.
 * Only same-origin `/api/` requests get the header so the token never leaks to third parties.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const token = inject(AuthService).token();
  if (!token || !req.url.startsWith('/api/')) {
    return next(req);
  }
  return next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
};
