import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import {
  Impersonation,
  ImpersonationExchange,
  Me,
  SignInResponse,
  TokenClaims,
} from '../../shared/models';
import { API_BASE } from '../api/api.config';

const AUTH = `${API_BASE}/auth`;

/**
 * Staff session (ADR 0010). The access token lives in memory only; the refresh token is an httpOnly
 * cookie the browser sends to `/api/v1/auth/session` alone, so a reload restores the session through
 * {@link restore}. Nothing here is a security boundary: the server checks every request.
 *
 * An impersonation (ADR 0017) is a session of its own: the token from the console's hand-off code, in
 * memory only, never refreshed, ended at its expiry or by End; a reload loses it by design.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly tokenSignal = signal<string | null>(null);
  private readonly meSignal = signal<Me | null>(null);
  private refreshing: Promise<boolean> | null = null;
  private readonly impersonationSignal = signal<Impersonation | null>(null);
  private impersonationTimer: ReturnType<typeof setTimeout> | null = null;

  readonly token = this.tokenSignal.asReadonly();
  readonly me = this.meSignal.asReadonly();
  readonly claims = computed<TokenClaims | null>(() => decodeClaims(this.tokenSignal()));
  readonly isAuthenticated = computed(() => this.tokenSignal() !== null);
  readonly impersonation = this.impersonationSignal.asReadonly();
  readonly impersonating = computed(() => this.impersonationSignal() !== null);
  /** Set when an impersonation ended here (End, expiry or a refused token), for the ended page. */
  readonly impersonationEnded = signal(false);

  login(tenant: string, email: string, password: string): Promise<SignInResponse> {
    return this.step(`${AUTH}/login`, { tenant, email, password });
  }

  changePassword(challenge: string, newPassword: string): Promise<SignInResponse> {
    return this.step(`${AUTH}/password`, { challenge, newPassword });
  }

  verifyTotp(challenge: string, code: string): Promise<SignInResponse> {
    return this.step(`${AUTH}/totp`, { challenge, code });
  }

  enrollTotp(challenge: string, code: string): Promise<SignInResponse> {
    return this.step(`${AUTH}/totp/enroll`, { challenge, code });
  }

  /**
   * A new access token from the refresh cookie; false when there is no valid session. Concurrent
   * callers in this tab share one call, and tabs take turns through a Web Lock: the server rotates the
   * refresh token on every use and treats a second use of the old one as theft, signing everyone out
   * (ADR 0010), so two tabs must never send the same cookie at once. The tab that waits sends the
   * cookie the first tab's refresh already rotated.
   */
  restore(): Promise<boolean> {
    if (this.impersonating()) {
      // An impersonation has no refresh token; when its token is refused, it is over.
      this.endedHere();
      return Promise.resolve(false);
    }
    this.refreshing ??= exclusively(() =>
      firstValueFrom(this.http.post<SignInResponse>(`${AUTH}/session/refresh`, null)),
    )
      .then(async (response) => {
        await this.accept(response);
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

  /** The hand-off page: the console's single-use code for the impersonation's token (ADR 0017). */
  async startImpersonation(code: string): Promise<void> {
    const answer = await firstValueFrom(
      this.http.post<ImpersonationExchange>(`${AUTH}/impersonation`, { code }),
    );
    this.clear();
    this.impersonationEnded.set(false);
    this.tokenSignal.set(answer.accessToken);
    const expiresAt = new Date(answer.expiresAt).getTime();
    this.impersonationSignal.set({
      id: answer.impersonationId,
      staffName: answer.staffName,
      platformAdminName: answer.platformAdminName,
      expiresAt,
    });
    this.impersonationTimer = setTimeout(
      () => this.endedHere(),
      Math.max(0, expiresAt - Date.now()),
    );
    this.meSignal.set(await firstValueFrom(this.http.get<Me>(`${API_BASE}/me`)));
  }

  /** End: the server ends the impersonation now; its token stops working at once. */
  async endImpersonation(): Promise<void> {
    try {
      await firstValueFrom(this.http.post(`${API_BASE}/impersonation/end`, null));
    } catch {
      // Ended locally either way; the token expires on its own.
    } finally {
      this.endedHere();
    }
  }

  private endedHere(): void {
    const was = this.impersonating();
    this.clear();
    if (was) {
      this.impersonationEnded.set(true);
    }
  }

  setToken(token: string | null): void {
    this.tokenSignal.set(token);
  }

  clear(): void {
    this.tokenSignal.set(null);
    this.meSignal.set(null);
    this.impersonationSignal.set(null);
    if (this.impersonationTimer) {
      clearTimeout(this.impersonationTimer);
      this.impersonationTimer = null;
    }
  }

  private async step(url: string, body: unknown): Promise<SignInResponse> {
    const response = await firstValueFrom(this.http.post<SignInResponse>(url, body));
    await this.accept(response);
    return response;
  }

  private async accept(response: SignInResponse): Promise<void> {
    if (response.status === 'SIGNED_IN' && response.accessToken) {
      this.impersonationEnded.set(false);
      this.tokenSignal.set(response.accessToken);
      this.meSignal.set(await firstValueFrom(this.http.get<Me>(`${API_BASE}/me`)));
    }
  }
}

/** Runs {@code work} holding the cross-tab refresh lock where the browser has Web Locks. */
function exclusively<T>(work: () => Promise<T>): Promise<T> {
  const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined;
  return locks ? locks.request('corebalt-staff-session-refresh', work) : work();
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
