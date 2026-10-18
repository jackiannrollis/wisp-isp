import { Injectable, computed, inject } from '@angular/core';

import { AuthService } from '../auth/auth.service';

/**
 * Reads permission codes (e.g. `customers.read`) from the access token claims.
 * STUB until real auth lands in step 8 of docs/09-roadmap.md. This only hides UI;
 * the server is the authority on permissions and enforces them on every request.
 */
@Injectable({ providedIn: 'root' })
export class PermissionService {
  private readonly auth = inject(AuthService);

  readonly permissions = computed(() => new Set(this.auth.claims()?.permissions ?? []));

  has(code: string): boolean {
    return this.permissions().has(code);
  }
}
