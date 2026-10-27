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
  /**
   * ADR 0017: false while Corebalt support impersonates. Actions that set or show a secret (a line's first
   * PPPoE password, a new one, router secrets, onboarding scripts, monitoring logins, the customer import)
   * are hidden then; the server refuses them anyway.
   */
  readonly mayHandleSecrets = computed(() => !this.auth.impersonating());

  has(code: string): boolean {
    return this.permissions().has(code);
  }
}
