import { Injectable, computed, signal } from '@angular/core';

import { TokenClaims } from '../../shared/models';

/**
 * STUB: holds the access token in memory and decodes its claims.
 * Real authentication (login, refresh, storage) lands in step 8 of docs/09-roadmap.md.
 * The server is the authority on identity and permissions; nothing here is a security boundary.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly tokenSignal = signal<string | null>(null);

  readonly token = this.tokenSignal.asReadonly();
  readonly claims = computed<TokenClaims | null>(() => decodeClaims(this.tokenSignal()));
  readonly isAuthenticated = computed(() => this.tokenSignal() !== null);

  setToken(token: string | null): void {
    this.tokenSignal.set(token);
  }

  clear(): void {
    this.tokenSignal.set(null);
  }
}

/** Decodes the payload of a JWT without verifying it (verification is the server's job). */
export function decodeClaims(token: string | null): TokenClaims | null {
  const payload = token?.split('.')[1];
  if (!payload) {
    return null;
  }
  try {
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
    const bytes = Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
    return JSON.parse(new TextDecoder().decode(bytes)) as TokenClaims;
  } catch {
    return null;
  }
}
