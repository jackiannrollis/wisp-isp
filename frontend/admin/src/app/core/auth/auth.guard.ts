import { CanActivateFn } from '@angular/router';

/**
 * STUB: lets every navigation through. Real authentication (redirect to login when
 * there is no valid session) lands in step 8 of docs/09-roadmap.md.
 * Guards only shape the UI; the server is the authority on access.
 */
export const authGuard: CanActivateFn = () => true;
