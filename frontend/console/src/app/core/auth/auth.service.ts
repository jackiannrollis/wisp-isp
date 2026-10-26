import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { PlatformClaims, SignInResponse } from '../../shared/models';
import { PLATFORM_API } from '../api/api.config';

const AUTH = `${PLATFORM_API}/auth`;

/**
 * Platform admin session (ADR 0010, ADR 0016). TOTP is mandatory: a first sign-in sets a new password,
 * then enrols the authenticator and shows the recovery codes once. The access token lives in memory
 * only; the refresh token is an httpOnly cookie the browser sends to `/api/platform/auth/session`
 * alone, so a reload restores the session through {@link restore}. Nothing here is a security
 * boundary: the server checks every request.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly tokenSignal = signal<string | null>(null);
  private refreshing: Promise<boolean> | null = null;

  readonly token = this.tokenSignal.asReadonly();
  readonly claims = computed<PlatformClaims | null>(() => decodeClaims(this.tokenSignal()));
  readonly isAuthenticated = computed(() => this.tokenSignal() !== null);

  login(email: string, password: string): Promise<SignInResponse> {
    return this.step(`${AUTH}/login`, { email, password });
  }

  changePassword(challenge: string, newPassword: string): Promise<SignInResponse> {
    return this.step(`${AUTH}/password`, { challenge, newPassword });
  }

  /** The authenticator's code, or a recovery code (XXXX-XXXX), each usable once. */
  verifyTotp(challenge: string, code: string): Promise<SignInResponse> {
    return this.step(`${AUTH}/totp`, { challenge, code });
  }

  enrollTotp(challenge: string, code: string): Promise<SignInResponse> {
    return this.step(`${AUTH}/totp/enroll`, { challenge, code });
  }

  /**
   * A new access token from the refresh cookie; false when there is no valid session. Concurrent
   * callers share one call, and tabs take turns through a Web Lock: the server rotates the refresh
   * token on every use and treats a second use of the old one as theft (ADR 0010).
   */
  restore(): Promise<boolean> {
    this.refreshing ??= exclusively(() =>
      firstValueFrom(this.http.post<SignInResponse>(`${AUTH}/session/refresh`, null)),
    )
      .then((response) => {
        this.accept(response);
        return this.isAuthenticated();
      })
      .catch(() => {
        this.clear();
        return false;
      })
      .finally(() => (this.refreshing = null));
    return this.refreshing;
  }

  async logout(): Promise<void> {
    try {
      await firstValueFrom(this.http.post(`${AUTH}/session/logout`, null));
    } catch {
      // Signed out locally either way; the refresh token expires on its own.
    } finally {
      this.clear();
    }
  }

  clear(): void {
    this.tokenSignal.set(null);
  }

  private async step(url: string, body: unknown): Promise<SignInResponse> {
    const response = await firstValueFrom(this.http.post<SignInResponse>(url, body));
    this.accept(response);
    return response;
  }

  private accept(response: SignInResponse): void {
    if (response.status === 'SIGNED_IN' && response.accessToken) {
      this.tokenSignal.set(response.accessToken);
    }
  }
}

/** Runs {@code work} holding the cross-tab refresh lock where the browser has Web Locks. */
function exclusively<T>(work: () => Promise<T>): Promise<T> {
  const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined;
  return locks ? locks.request('corebalt-platform-session-refresh', work) : work();
}

/** Decodes the payload of a JWT without verifying it (verification is the server's job). */
export function decodeClaims(token: string | null): PlatformClaims | null {
  const payload = token?.split('.')[1];
  if (!payload) {
    return null;
  }
  try {
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
    const bytes = Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
    return JSON.parse(new TextDecoder().decode(bytes)) as PlatformClaims;
  } catch {
    return null;
  }
}
